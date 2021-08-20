/*
 * Copyright © 2021 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.gcp.dataplex.sink.connector;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.Lists;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.cdap.etl.api.batch.BatchSink;
import io.cdap.cdap.etl.api.connector.BrowseDetail;
import io.cdap.cdap.etl.api.connector.BrowseEntity;
import io.cdap.cdap.etl.api.connector.BrowseRequest;
import io.cdap.cdap.etl.api.connector.Connector;
import io.cdap.cdap.etl.api.connector.ConnectorContext;
import io.cdap.cdap.etl.api.connector.ConnectorSpec;
import io.cdap.cdap.etl.api.connector.ConnectorSpecRequest;
import io.cdap.cdap.etl.api.connector.DirectConnector;
import io.cdap.cdap.etl.api.connector.PluginSpec;
import io.cdap.cdap.etl.api.connector.SampleRequest;
import io.cdap.cdap.etl.api.validation.ValidationException;
import io.cdap.plugin.common.ConfigUtil;
import io.cdap.plugin.gcp.common.GCPUtils;
import io.cdap.plugin.gcp.dataplex.sink.DataplexBatchSink;
import io.cdap.plugin.gcp.dataplex.sink.config.DataplexBaseConfig;
import io.cdap.plugin.gcp.dataplex.sink.connection.DataplexInterface;
import io.cdap.plugin.gcp.dataplex.sink.connection.out.DataplexInterfaceImpl;
import io.cdap.plugin.gcp.dataplex.sink.exception.ConnectorException;
import io.cdap.plugin.gcp.dataplex.sink.model.Asset;
import io.cdap.plugin.gcp.dataplex.sink.model.Lake;
import io.cdap.plugin.gcp.dataplex.sink.model.Location;
import io.cdap.plugin.gcp.dataplex.sink.model.Zone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dataplex Connector Plugin
 */
@Plugin(type = Connector.PLUGIN_TYPE)
@Name(DataplexConnector.NAME)
@Description("This connector enables browsing feature to fetch the locations, lakes, zones and assets information " +
  "from Dataplex.")
public class DataplexConnector implements DirectConnector {
    public static final String NAME = "Dataplex";
    private static final String DATAPLEX_LOCATION = "Location";
    private static final String DATAPLEX_LAKE = "Lake";
    private static final String DATAPLEX_ZONE = "Zone";
    private static final String DATAPLEX_ASSET = "Asset";
    private static final Logger LOG = LoggerFactory.getLogger(DataplexConnector.class);
    private static DataplexInterface dataplexInterface = new DataplexInterfaceImpl();
    private DataplexConnectorConfig config;

    DataplexConnector(DataplexConnectorConfig config) {
        this.config = config;
    }

    @Override
    public void test(ConnectorContext context) throws ValidationException {
        FailureCollector failureCollector = context.getFailureCollector();
        // validate project ID
        String project = config.tryGetProject();
        if (project == null) {
            failureCollector
              .addFailure("Could not detect Google Cloud project id from the environment.",
                "Please specify a project id.");
        }

        GoogleCredentials credentials = null;

        if (config.isServiceAccountJson() || config.getServiceAccountFilePath() != null) {
            try {
                credentials = getCredentials();
            } catch (Exception e) {
                failureCollector.addFailure(String.format("Service account key provided is not valid: %s.",
                  e.getMessage()), "Please provide a valid service account key.");
            }
        }
        // if either project or credentials cannot be loaded , no need to continue
        if (!failureCollector.getValidationFailures().isEmpty()) {
            return;
        }

        try {
            dataplexInterface.listLocations(credentials,
              config.tryGetProject());
        } catch (Exception e) {
            failureCollector.addFailure(String.format("Could not connect to Dataplex: %s", e.getMessage()),
              "Please specify correct connection properties.");
        }
    }

    @Override
    public BrowseDetail browse(ConnectorContext connectorContext, BrowseRequest browseRequest) throws IOException {
        DataplexPath path = new DataplexPath(browseRequest.getPath());
        String location = path.getLocation();
        if (location == null) {
            try {
                return listLocations(browseRequest.getLimit());
            } catch (ConnectorException e) {
                LOG.debug(e.getCode() + ": " + e.getMessage());
            }
        }

        String lake = path.getLake();
        if (lake == null) {
            try {
                return listLakes(path, browseRequest.getLimit());
            } catch (ConnectorException e) {
                LOG.debug(e.getCode() + ": " + e.getMessage());
            }
        }
        String zone = path.getZone();
        if (zone == null) {
            try {
                return listZones(path, browseRequest.getLimit());
            } catch (ConnectorException e) {
                LOG.debug(e.getCode() + ": " + e.getMessage());
            }
        }
        String asset = path.getAsset();
        if (asset == null) {
            try {
                return listAssets(path, browseRequest.getLimit());
            } catch (ConnectorException e) {
                LOG.debug(e.getCode() + ": " + e.getMessage());
            }
        }
        BrowseDetail.Builder builder = BrowseDetail.builder();
        builder.addEntity(BrowseEntity.builder(asset, asset, "Asset").canBrowse(false).canSample(true).build());
        return builder.setTotalCount(1).build();
    }

    private BrowseDetail listLocations(Integer limit) throws IOException, ConnectorException {
        int countLimit = limit == null || limit <= 0 ? Integer.MAX_VALUE : limit;
        int count = 0;
        BrowseDetail.Builder builder = BrowseDetail.builder();
        List<Location> locationList = dataplexInterface.listLocations(getCredentials(),
          config.tryGetProject());
        for (Location location : locationList) {
            if (count >= countLimit) {
                break;
            }
            builder.addEntity(
              BrowseEntity.builder(location.getLocationId(), "/" + location.getLocationId(), DATAPLEX_LOCATION)
                .canSample(true).canBrowse(true).build());
            count++;
        }
        return builder.setTotalCount(count).build();
    }

    private GoogleCredentials getCredentials() throws IOException {
        GoogleCredentials credentials = null;
        // validate service account
        if (config.isServiceAccountJson() || config.getServiceAccountFilePath() != null) {
            credentials =
              GCPUtils.loadServiceAccountCredentials(config.getServiceAccount(), config.isServiceAccountFilePath())
                .createScoped(Lists.newArrayList("https://www.googleapis.com/auth/cloud-platform"));
        }
        return credentials;
    }

    private BrowseDetail listLakes(DataplexPath path, Integer limit) throws IOException, ConnectorException {
        int countLimit = limit == null || limit <= 0 ? Integer.MAX_VALUE : limit;
        int count = 0;
        BrowseDetail.Builder builder = BrowseDetail.builder();
        String parentPath = String.format("/%s/", path.getLocation());
        List<Lake> lakeList = dataplexInterface.listLakes(getCredentials(),
          config.tryGetProject(), path.getLocation());
        for (Lake lake : lakeList) {
            if (count >= countLimit) {
                break;
            }
            builder.addEntity(
              BrowseEntity.builder(getObjectId(lake.getName()), parentPath + getObjectId(lake.getName()), DATAPLEX_LAKE)
                .canBrowse(true).canSample(true).build());
            count++;
        }
        return builder.setTotalCount(count).build();
    }


    private BrowseDetail listZones(DataplexPath path, Integer limit) throws IOException, ConnectorException {
        int countLimit = limit == null || limit <= 0 ? Integer.MAX_VALUE : limit;
        int count = 0;
        BrowseDetail.Builder builder = BrowseDetail.builder();
        String parentPath = String.format("/%s/%s/", path.getLocation(), path.getLake());
        List<Zone> zonelist = dataplexInterface.listZones(getCredentials(),
          config.tryGetProject(), path.getLocation(), path.getLake());
        for (Zone zone : zonelist) {
            if (count >= countLimit) {
                break;
            }
            builder.addEntity(
              BrowseEntity.builder(getObjectId(zone.getName()), parentPath + getObjectId(zone.getName()),
                toCamelCase(zone.getType()) + " " + DATAPLEX_ZONE)
                .canBrowse(true).canSample(true).build());
            count++;
        }
        return builder.setTotalCount(count).build();
    }

    private BrowseDetail listAssets(DataplexPath path, Integer limit) throws IOException, ConnectorException {
        int countLimit = limit == null || limit <= 0 ? Integer.MAX_VALUE : limit;
        int count = 0;
        BrowseDetail.Builder builder = BrowseDetail.builder();
        String parentPath = String.format("/%s/%s/%s/", path.getLocation(), path.getLake(), path.getZone());
        List<Asset> assetlist = dataplexInterface.listAssets(getCredentials(),
          config.tryGetProject(), path.getLocation(), path.getLake(), path.getZone());
        for (Asset asset : assetlist) {
            if (count >= countLimit) {
                break;
            }
            builder.addEntity(
              BrowseEntity.builder(getObjectId(asset.getName()), parentPath + getObjectId(asset.getName()),
                toCamelCase(asset.getAssetResourceSpec().getType()))
                .canSample(true).build());
            count++;
        }
        return builder.setTotalCount(count).build();
    }

    @Override
    public ConnectorSpec generateSpec(ConnectorContext connectorContext, ConnectorSpecRequest connectorSpecRequest)
      throws IOException {
        ConnectorSpec.Builder specBuilder = ConnectorSpec.builder();
        DataplexPath path = new DataplexPath(connectorSpecRequest.getPath());

        Map<String, String> properties = new HashMap<>();
        properties.put(ConfigUtil.NAME_USE_CONNECTION, "true");
        properties.put(ConfigUtil.NAME_CONNECTION, connectorSpecRequest.getConnectionWithMacro());
        properties.put(DataplexBaseConfig.NAME_LOCATION, path.getLocation());
        properties.put(DataplexBaseConfig.NAME_LAKE, path.getLake());
        properties.put(DataplexBaseConfig.NAME_ZONE, path.getZone());
        properties.put(DataplexBaseConfig.NAME_ASSET, path.getAsset());
        Asset asset = null;
        try {
            asset = dataplexInterface.getAsset(getCredentials(),
              config.tryGetProject(), path.getLocation(), path.getLake(), path.getZone(), path.getAsset());
        } catch (ConnectorException e) {
            LOG.debug(e.getCode() + ": " + e.getMessage());
        }
        properties.put(DataplexBaseConfig.NAME_ASSET_TYPE, asset.getAssetResourceSpec().type);
        // specBuilder.setSchema(Schema.of(Schema.Type.NULL));
        return specBuilder.addRelatedPlugin(new PluginSpec(DataplexBatchSink.NAME, BatchSink.PLUGIN_TYPE, properties))
          .build();
    }

    @Override
    public List<StructuredRecord> sample(ConnectorContext connectorContext, SampleRequest sampleRequest)
      throws IOException {
        return Collections.emptyList();
    }

    private String getObjectId(String name) {
        return name.substring(name.lastIndexOf('/') + 1);
    }

    private String toCamelCase(String value) {
        String[] words = value.split("_");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            word = word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase();
            builder.append(" ");
            builder.append(word);
        }
        return builder.toString().trim();
    }
}
