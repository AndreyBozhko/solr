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
package org.apache.solr.client.api.endpoint;

import static org.apache.solr.client.api.util.Constants.GENERIC_ENTITY_PROPERTY;
import static org.apache.solr.client.api.util.Constants.RAW_OUTPUT_PROPERTY;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.io.InputStream;
import java.util.List;
import org.apache.solr.client.api.model.FileStoreDirectoryListingResponse;
import org.apache.solr.client.api.model.SolrJerseyResponse;
import org.apache.solr.client.api.model.UploadToFileStoreResponse;

@Path("/cluster/filestore")
public interface ClusterFileStoreApis {
  // TODO Better understand the purpose of the 'sig' parameter and improve docs here.
  @PUT
  @Operation(
      summary = "Upload a file to the filestore.",
      tags = {"file-store"})
  @Path("/files{filePath:.+}")
  UploadToFileStoreResponse uploadFile(
      @Parameter(description = "File store path") @PathParam("filePath") String filePath,
      @Parameter(description = "Signature(s) for the file being uploaded") @QueryParam("sig")
          List<String> sig,
      @Parameter(description = "File content to be stored in the filestore")
          @RequestBody(
              required = true,
              extensions = {
                @Extension(
                    properties = {
                      @ExtensionProperty(name = GENERIC_ENTITY_PROPERTY, value = "true")
                    })
              })
          InputStream requestBody);

  @GET
  @Operation(
      summary = "Retrieve metadata about a file or directory in the filestore.",
      tags = {"file-store"})
  @Path("/metadata{path:.+}")
  FileStoreDirectoryListingResponse getMetadata(
      @Parameter(description = "Path to a file or directory within the filestore")
          @PathParam("path")
          String path);

  @GET
  @Operation(
      summary = "Retrieve raw contents of a file in the filestore.",
      tags = {"file-store"},
      extensions = {
        @Extension(properties = {@ExtensionProperty(name = RAW_OUTPUT_PROPERTY, value = "true")})
      })
  @Path("/files{filePath:.+}")
  SolrJerseyResponse getFile(
      @Parameter(description = "Path to a file or directory within the filestore")
          @PathParam("filePath")
          String path);

  @DELETE
  @Operation(
      summary = "Delete a file or directory from the filestore.",
      tags = {"file-store"})
  @Path("/files{path:.+}")
  SolrJerseyResponse deleteFile(
      @Parameter(description = "Path to a file or directory within the filestore")
          @PathParam("path")
          String path,
      @Parameter(
              description =
                  "Indicates whether the deletion should only be done on the receiving node.  For internal use only")
          @QueryParam("localDelete")
          Boolean localDelete);

  @POST
  @Operation(
      summary = "Fetches a filestore entry from other nodes in the cluster.",
      tags = {"file-store"})
  @Path("/commands/fetch{path:.+}")
  SolrJerseyResponse fetchFile(
      @Parameter(description = "Path to a file or directory within the filestore")
          @PathParam("path")
          String path,
      @Parameter(description = "An optional Solr node name to fetch the file from")
          @QueryParam("getFrom")
          String getFrom);

  @POST
  @Operation(
      summary = "Syncs a file by pushing it to other nodes in the cluster.",
      tags = {"file-store"})
  @Path("/commands/sync{path:.+}")
  SolrJerseyResponse syncFile(
      @Parameter(description = "Path to a file or directory within the filestore")
          @PathParam("path")
          String path);
}
