/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.admin.CoreAdminHandler;
import org.apache.solr.response.SolrQueryResponse;
import org.junit.After;
import org.junit.Before;

// Backups do checksum validation against a footer value not present in 'SimpleText'
@LuceneTestCase.SuppressCodecs({"SimpleText"})
public class TestSnapshotCoreBackup extends SolrTestCaseJ4 {
  @Before // unique core per test
  public void coreInit() throws Exception {
    initCore("solrconfig.xml", "schema.xml");
  }

  @After // unique core per test
  public void coreDestroy() {
    deleteCore();
  }

  public void testBackupWithDocsNotSearchable() throws Exception {
    // See SOLR-11616 to see when this issue can be triggered

    assertU(adoc("id", "1"));
    assertU(commit());

    assertU(adoc("id", "2"));

    assertU(commit("openSearcher", "false"));
    assertQ(req("q", "*:*"), "//result[@numFound='1']");
    assertQ(req("q", "id:1"), "//result[@numFound='1']");
    assertQ(req("q", "id:2"), "//result[@numFound='0']");

    // call backup
    Path location = createTempDir();
    String snapshotName = TestUtil.randomSimpleString(random(), 1, 5);

    final CoreContainer cores = h.getCoreContainer();
    cores.getAllowPaths().add(location);
    try (final CoreAdminHandler admin = new CoreAdminHandler(cores)) {
      SolrQueryResponse resp = new SolrQueryResponse();
      admin.handleRequestBody(
          req(
              CoreAdminParams.ACTION,
              CoreAdminParams.CoreAdminAction.BACKUPCORE.toString(),
              "core",
              DEFAULT_TEST_COLLECTION_NAME,
              "name",
              snapshotName,
              "location",
              location.toString(),
              CoreAdminParams.BACKUP_INCREMENTAL,
              "false"),
          resp);
      assertNull("Backup should have succeeded", resp.getException());
      simpleBackupCheck(location.resolve("snapshot." + snapshotName), 2);
    }
  }

  public void testBackupBeforeFirstCommit() throws Exception {

    // even without a user sending any data, the SolrCore initialization logic should have
    // automatically
    // created an "empty" commit point that can be backed up...
    final IndexCommit empty = h.getCore().getDeletionPolicy().getLatestCommit();
    assertNotNull(empty);

    // white box sanity check that the commit point of the "reader" available from SolrIndexSearcher
    // matches the commit point that IDPW claims is the "latest"
    //
    // this is important to ensure that backup/snapshot behavior is consistent with user expectation
    // when using typical commit + openSearcher
    assertEquals(empty, h.getCore().withSearcher(s -> s.getIndexReader().getIndexCommit()));

    assertEquals(1L, empty.getGeneration());
    assertNotNull(empty.getSegmentsFileName());
    final String initialEmptyIndexSegmentFileName = empty.getSegmentsFileName();

    final CoreContainer cores = h.getCoreContainer();
    final CoreAdminHandler admin = new CoreAdminHandler(cores);

    final Path backupDir = createTempDir();
    cores.getAllowPaths().add(backupDir);

    { // first a backup before we've ever done *anything*...
      SolrQueryResponse resp = new SolrQueryResponse();
      admin.handleRequestBody(
          req(
              CoreAdminParams.ACTION,
              CoreAdminParams.CoreAdminAction.BACKUPCORE.toString(),
              "core",
              DEFAULT_TEST_COLLECTION_NAME,
              "name",
              "empty_backup1",
              "location",
              backupDir.toString(),
              CoreAdminParams.BACKUP_INCREMENTAL,
              "false"),
          resp);
      assertNull("Backup should have succeeded", resp.getException());
      simpleBackupCheck(
          backupDir.resolve("snapshot.empty_backup1"), 0, initialEmptyIndexSegmentFileName);
    }

    { // Empty (named) snapshot...
      SolrQueryResponse resp = new SolrQueryResponse();
      admin.handleRequestBody(
          req(
              CoreAdminParams.ACTION,
              CoreAdminParams.CoreAdminAction.CREATESNAPSHOT.toString(),
              "core",
              DEFAULT_TEST_COLLECTION_NAME,
              "commitName",
              "empty_snapshotA"),
          resp);
      assertNull("Snapshot A should have succeeded", resp.getException());
    }

    assertU(adoc("id", "1")); // uncommitted

    { // second backup with uncommitted docs
      SolrQueryResponse resp = new SolrQueryResponse();
      admin.handleRequestBody(
          req(
              CoreAdminParams.ACTION,
              CoreAdminParams.CoreAdminAction.BACKUPCORE.toString(),
              "core",
              DEFAULT_TEST_COLLECTION_NAME,
              "name",
              "empty_backup2",
              "location",
              backupDir.toString(),
              CoreAdminParams.BACKUP_INCREMENTAL,
              "false"),
          resp);
      assertNull("Backup should have succeeded", resp.getException());
      simpleBackupCheck(
          backupDir.resolve("snapshot.empty_backup2"), 0, initialEmptyIndexSegmentFileName);
    }

    { // Second empty (named) snapshot...
      SolrQueryResponse resp = new SolrQueryResponse();
      admin.handleRequestBody(
          req(
              CoreAdminParams.ACTION,
              CoreAdminParams.CoreAdminAction.CREATESNAPSHOT.toString(),
              "core",
              DEFAULT_TEST_COLLECTION_NAME,
              "commitName",
              "empty_snapshotB"),
          resp);
      assertNull("Snapshot A should have succeeded", resp.getException());
    }

    // Committing the doc now should not affect the existing backups or snapshots...
    assertU(commit());

    for (String name : Arrays.asList("empty_backup1", "empty_backup2")) {
      simpleBackupCheck(backupDir.resolve("snapshot." + name), 0, initialEmptyIndexSegmentFileName);
    }

    // Make backups from each of the snapshots and check they are still empty as well...
    for (String snapName : Arrays.asList("empty_snapshotA", "empty_snapshotB")) {
      String name = "empty_backup_from_" + snapName;
      SolrQueryResponse resp = new SolrQueryResponse();

      admin.handleRequestBody(
          req(
              CoreAdminParams.ACTION,
              CoreAdminParams.CoreAdminAction.BACKUPCORE.toString(),
              "core",
              DEFAULT_TEST_COLLECTION_NAME,
              "name",
              name,
              "commitName",
              snapName,
              "location",
              backupDir.toString(),
              CoreAdminParams.BACKUP_INCREMENTAL,
              "false"),
          resp);
      assertNull("Backup " + name + " should have succeeded", resp.getException());
      simpleBackupCheck(backupDir.resolve("snapshot." + name), 0, initialEmptyIndexSegmentFileName);
    }
    admin.close();
  }

  /** Tests that a softCommit does not affect what data is in a backup */
  public void testBackupAfterSoftCommit() throws Exception {

    // sanity check empty index...
    assertQ(req("q", "id:42"), "//result[@numFound='0']");
    assertQ(req("q", "id:99"), "//result[@numFound='0']");
    assertQ(req("q", "*:*"), "//result[@numFound='0']");

    // hard commit one doc...
    assertU(adoc("id", "99"));
    assertU(commit());
    assertQ(req("q", "id:99"), "//result[@numFound='1']");
    assertQ(req("q", "*:*"), "//result[@numFound='1']");

    final IndexCommit oneDocCommit = h.getCore().getDeletionPolicy().getLatestCommit();
    assertNotNull(oneDocCommit);
    final String oneDocSegmentFile = oneDocCommit.getSegmentsFileName();

    final CoreContainer cores = h.getCoreContainer();
    final CoreAdminHandler admin = new CoreAdminHandler(cores);

    final Path backupDir = createTempDir();
    cores.getAllowPaths().add(backupDir);

    { // take an initial 'backup1a' containing our 1 document
      final SolrQueryResponse resp = new SolrQueryResponse();
      admin.handleRequestBody(
          req(
              CoreAdminParams.ACTION,
              CoreAdminParams.CoreAdminAction.BACKUPCORE.toString(),
              "core",
              DEFAULT_TEST_COLLECTION_NAME,
              "name",
              "backup1a",
              "location",
              backupDir.toString(),
              CoreAdminParams.BACKUP_INCREMENTAL,
              "false"),
          resp);
      assertNull("Backup should have succeeded", resp.getException());
      simpleBackupCheck(backupDir.resolve("snapshot.backup1a"), 1, oneDocSegmentFile);
    }

    { // and an initial "snapshot1a' that should eventually match
      SolrQueryResponse resp = new SolrQueryResponse();
      admin.handleRequestBody(
          req(
              CoreAdminParams.ACTION,
              CoreAdminParams.CoreAdminAction.CREATESNAPSHOT.toString(),
              "core",
              DEFAULT_TEST_COLLECTION_NAME,
              "commitName",
              "snapshot1a"),
          resp);
      assertNull("Snapshot 1A should have succeeded", resp.getException());
    }

    // now we add our 2nd doc, and make it searchable, but we do *NOT* hard commit it to the index
    // dir...
    assertU(adoc("id", "42"));
    assertU(commit("softCommit", "true", "openSearcher", "true"));

    assertQ(req("q", "id:99"), "//result[@numFound='1']");
    assertQ(req("q", "id:42"), "//result[@numFound='1']");
    assertQ(req("q", "*:*"), "//result[@numFound='2']");

    { // we now have an index with two searchable docs, but a new 'backup1b' should still
      // be identical to the previous backup...
      final SolrQueryResponse resp = new SolrQueryResponse();
      admin.handleRequestBody(
          req(
              CoreAdminParams.ACTION,
              CoreAdminParams.CoreAdminAction.BACKUPCORE.toString(),
              "core",
              DEFAULT_TEST_COLLECTION_NAME,
              "name",
              "backup1b",
              "location",
              backupDir.toString(),
              CoreAdminParams.BACKUP_INCREMENTAL,
              "false"),
          resp);
      assertNull("Backup should have succeeded", resp.getException());
      simpleBackupCheck(backupDir.resolve("snapshot.backup1b"), 1, oneDocSegmentFile);
    }

    { // and a second "snapshot1b' should also still be identical
      SolrQueryResponse resp = new SolrQueryResponse();
      admin.handleRequestBody(
          req(
              CoreAdminParams.ACTION,
              CoreAdminParams.CoreAdminAction.CREATESNAPSHOT.toString(),
              "core",
              DEFAULT_TEST_COLLECTION_NAME,
              "commitName",
              "snapshot1b"),
          resp);
      assertNull("Snapshot 1B should have succeeded", resp.getException());
    }

    // Hard Committing the 2nd doc now should not affect the existing backups or snapshots...
    assertU(commit());

    for (String name : Arrays.asList("backup1a", "backup1b")) {
      simpleBackupCheck(backupDir.resolve("snapshot." + name), 1, oneDocSegmentFile);
    }

    { // But we should be able to confirm both docs appear in a new backup (not based on a previous
      // snapshot)
      final SolrQueryResponse resp = new SolrQueryResponse();
      admin.handleRequestBody(
          req(
              CoreAdminParams.ACTION,
              CoreAdminParams.CoreAdminAction.BACKUPCORE.toString(),
              "core",
              DEFAULT_TEST_COLLECTION_NAME,
              "name",
              "backup2",
              "location",
              backupDir.toString(),
              CoreAdminParams.BACKUP_INCREMENTAL,
              "false"),
          resp);
      assertNull("Backup should have succeeded", resp.getException());
      simpleBackupCheck(backupDir.resolve("snapshot.backup2"), 2);
    }

    // if we go back and create backups from our earlier snapshots they should still only
    // have 1 expected doc...
    // Make backups from each of the snapshots and check they are still empty as well...
    for (String snapName : Arrays.asList("snapshot1a", "snapshot1b")) {
      String name = "backup_from_" + snapName;
      SolrQueryResponse resp = new SolrQueryResponse();
      admin.handleRequestBody(
          req(
              CoreAdminParams.ACTION,
              CoreAdminParams.CoreAdminAction.BACKUPCORE.toString(),
              "core",
              DEFAULT_TEST_COLLECTION_NAME,
              "name",
              name,
              "commitName",
              snapName,
              "location",
              backupDir.toString(),
              CoreAdminParams.BACKUP_INCREMENTAL,
              "false"),
          resp);
      assertNull("Backup " + name + " should have succeeded", resp.getException());
      simpleBackupCheck(backupDir.resolve("snapshot." + name), 1, oneDocSegmentFile);
    }
    admin.close();
  }

  /**
   * A simple sanity check that asserts the current weird behavior of
   * DirectoryReader.openIfChanged() and demos how 'softCommit' can cause the IndexReader in use by
   * SolrIndexSearcher to misrepresent what commit is "current". So Backup code should only ever
   * "trust" the IndexCommit info available from the IndexDeletionPolicyWrapper
   *
   * @see <a href="https://issues.apache.org/jira/browse/LUCENE-9040">LUCENE-9040</a>
   * @see <a href="https://issues.apache.org/jira/browse/SOLR-13909">SOLR-13909</a>
   */
  public void testDemoWhyBackupCodeShouldNeverUseIndexCommitFromSearcher() throws Exception {

    final long EXPECTED_GEN_OF_EMPTY_INDEX = 1L;

    // sanity check this is an empty index...
    assertQ(req("q", "*:*"), "//result[@numFound='0']");

    // sanity check what the searcher/reader of this empty index report about current commit
    final IndexCommit empty =
        h.getCore()
            .withSearcher(
                s -> {
                  // sanity check we are empty...
                  assertEquals(0L, (long) s.getIndexReader().numDocs());

                  // check this is the initial commit...
                  final IndexCommit commit = s.getIndexReader().getIndexCommit();
                  assertEquals(EXPECTED_GEN_OF_EMPTY_INDEX, commit.getGeneration());
                  return commit;
                });

    // now let's add & soft commit 1 doc...
    assertU(adoc("id", "42"));
    assertU(commit("softCommit", "true", "openSearcher", "true"));

    // verify it's "searchable" ...
    assertQ(req("q", "id:42"), "//result[@numFound='1']");

    // sanity check what the searcher/reader of this empty index report about current commit
    IndexCommit oneDoc =
        h.getCore()
            .withSearcher(
                s -> {
                  // sanity check this really is the searcher/reader that has the new doc...
                  assertEquals(1L, (long) s.getIndexReader().numDocs());

                  final IndexCommit commit = s.getIndexReader().getIndexCommit();
                  // WTF: how/why does this reader still have the same commit generation as before?
                  assertEquals(
                      "WTF: This Reader (claims) the same generation as our previous pre-softCommit (empty) reader",
                      EXPECTED_GEN_OF_EMPTY_INDEX,
                      commit.getGeneration());
                  return commit;
                });

    assertEquals(
        "WTF: Our two IndexCommits, which we know have different docs, claim to be equals",
        empty,
        oneDoc);
  }

  /**
   * Simple check that the backup exists, is a valid index, and contains the expected number of docs
   */
  private static void simpleBackupCheck(final Path backup, final int numDocs) throws IOException {
    simpleBackupCheck(backup, numDocs, null);
  }

  /**
   * Simple check that the backup exists, is a valid index, and contains the expected number of
   * docs. If expectedSegmentsFileName is non-null then confirms that file exists in the backup dir
   * <em>and</em> that it is reported as the current segment file when opening a reader on that
   * backup.
   */
  private static void simpleBackupCheck(
      final Path backup, final int numDocs, final String expectedSegmentsFileName)
      throws IOException {
    assertNotNull(backup);
    assertTrue("Backup doesn't exist" + backup, Files.exists(backup));
    if (null != expectedSegmentsFileName) {
      assertTrue(
          expectedSegmentsFileName + " doesn't exist in " + backup,
          Files.exists(backup.resolve(expectedSegmentsFileName)));
    }
    try (Directory dir = FSDirectory.open(backup)) {
      TestUtil.checkIndex(dir, true, true, true, null);
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        assertEquals("numDocs in " + backup, numDocs, r.numDocs());
        if (null != expectedSegmentsFileName) {
          assertEquals(
              "segmentsFile of IndexCommit for: " + backup,
              expectedSegmentsFileName,
              r.getIndexCommit().getSegmentsFileName());
        }
      }
    }
  }
}
