/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.mapreduce.lib.bulkimport;

import java.io.IOException;

import com.google.common.base.Preconditions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.annotations.ApiAudience;
import org.kiji.mapreduce.KijiTableContext;
import org.kiji.schema.EntityId;
import org.kiji.schema.KijiColumnName;

/**
 * Bulk importer that handles JSON files.  The expected JSON file should be a set of records
 * separated by new lines
 * (see {@link org.apache.hadoop.mapreduce.lib.input.TextInputFormat TextInputFormat}).
 * Each line represents a separate JSON object to be imported into a row.  Target
 * columns whose sources are not present in the JSON object are skipped.  This bulk importer uses
 * <a href="http://code.google.com/p/google-gson/">google-gson</a> to parse lines into fields.
 *
 * Complex paths in JSON are specified by strings delimited with periods(.).
 *
 * <h2>Creating a bulk import job for JSON files:</h2>
 * <p>
 *   The bulk importer can be passed into a
 *   {@link org.kiji.mapreduce.bulkimport.KijiBulkImportJobBuilder}.  A
 *   {@link KijiTableImportDescriptor}, which defines the mapping from the import fields to the
 *   destination Kiji columns, must be passed in as part of the job configuration.  For writing
 *   to an HFile which can later be loaded with the <code>kiji bulk-load</code> tool the job
 *   creation looks like:
 * </p>
 * <pre><code>
 *   // Set the import descriptor file to be used for this bulk importer.
 *   conf.set(DescribedInputTextBulkImporter.CONF_FILE, "foo-test-import-descriptor.json");
 *   // Configure and create the MapReduce job.
 *   final MapReduceJob job = KijiBulkImportJobBuilder.create()
 *       .withConf(conf)
 *       .withBulkImporter(JSONBulkImporter.class)
 *       .withInput(MapReduceJobInputs.newTextMapReduceJobInput(new Path(inputFile.toString())))
 *       .withOutput(MapReduceJobOutputs.newHFileMapReduceJobOutput(mOutputTable, hfileDirPath))
 *       .build();
 * </code></pre>
 * <p>
 *   Alternately the bulk importer can be configured to write directly to a Kiji Table.  This is
 *   <em>not recommended</em> because it generates individual puts for each cell that is being
 *   written. For small jobs or tests, a direct Kiji table output job can be created by modifying
 *   out the .withOutput parameter to:
 *   <code>.withOutput(MapReduceJobOutputs
 *       .newDirectKijiTableMapReduceJobOutput(mOutputTable))</code>
 * </p>
 *
 * @see KijiTableImportDescriptor
 */
@ApiAudience.Public
public final class JSONBulkImporter extends DescribedInputTextBulkImporter {
  private static final Logger LOG = LoggerFactory.getLogger(JSONBulkImporter.class);

  /**
   * Returns a string containing an element referenced by the specified path, or null if the
   * element isn't found.  This uses a period '.' delimited syntax similar to JSONPath
   * ({@linktourl http://goessner.net/articles/JsonPath/}).
   *
   * TODO(KIJIMRLIB-5) Use an enhanced JSONPath library for this functionality.
   *
   * @param head JsonObject that is the head of the current JSON tree.
   * @param path delimited by periods
   * @return string denoting the element at the specified path.
   */
  private String getFromPath(JsonObject head, String path) {
    Preconditions.checkNotNull(head);
    Preconditions.checkNotNull(path);

    // Split the path into components using the delimiter for tree traversal.
    String[] pathComponents = path.split("\\.");

    // After getting the path components traverse the json tree.
    JsonElement jsonElement = head;
    for (String pathComponent : pathComponents) {
      if (jsonElement.isJsonObject()) {
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        if (jsonObject.has(pathComponent)) {
          jsonElement = jsonObject.get(pathComponent);
        } else {
          LOG.warn("Missing path component {} at current path {}.  Returning null.",
              pathComponent, jsonObject);
          return null;
        }
      }
    }
    if (jsonElement.isJsonPrimitive()) {
      return jsonElement.getAsString();
    }
    LOG.warn("Specified path {} is not complete for {}.  Returning null", path, head);
    return null;
  }

  /** {@inheritDoc} */
  @Override
  public void produce(Text value, KijiTableContext context) throws IOException {
    JsonObject gson = new JsonParser().parse(value.toString()).getAsJsonObject();

    for (KijiColumnName kijiColumnName : getDestinationColumns()) {
      String entityIdSource = getFromPath(gson, getEntityIdSource());
      if (entityIdSource == null) {
        LOG.error("Unable to retrieve entityId from source field: " + getEntityIdSource());
        return;
      }
      final EntityId eid = context.getEntityId(entityIdSource);
      String source = getSource(kijiColumnName);
      String fieldValue = getFromPath(gson, source);
      if (fieldValue != null) {
        String family = kijiColumnName.getFamily();
        String qualifier = kijiColumnName.getQualifier();
        if (isOverrideTimestamp()) {
          // Override the timestamp from the imported source
          String timestampSource = getFromPath(gson, getTimestampSource());
          Long timestamp = Long.parseLong(timestampSource);
          context.put(eid, family, qualifier, timestamp, convert(kijiColumnName, fieldValue));
        } else {
          // Use the system time as the timestamp
          context.put(eid, family, qualifier, convert(kijiColumnName, fieldValue));
        }
      } else {
        incomplete(value, context, "Detected missing field: " + source);
      }
    }
  }
}
