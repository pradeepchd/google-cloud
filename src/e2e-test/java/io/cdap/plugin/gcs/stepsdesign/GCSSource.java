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
package io.cdap.plugin.gcs.stepsdesign;

import io.cdap.e2e.pages.actions.CdfGcsActions;
import io.cdap.e2e.pages.locators.CdfGCSLocators;
import io.cdap.e2e.utils.CdfHelper;
import io.cdap.e2e.utils.PluginPropertyUtils;
import io.cdap.e2e.utils.SeleniumHelper;
import io.cdap.plugin.common.stepsdesign.TestSetupHooks;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.io.IOException;

/**
 * GCS Source Plugin related step design.
 */
public class GCSSource implements CdfHelper {

  @When("Source is GCS")
  public void sourceIsGCS() {
    selectSourcePlugin("GCSFile");
  }

  @Then("Open GCS source properties")
  public void openGCSSourceProperties() {
    openSourcePluginProperties("GCS");
  }

  @Then("Enter GCS source property path {string}")
  public void enterGCSSourcePropertyPath(String path) {
    CdfGcsActions.getGcsBucket("gs://" + TestSetupHooks.gcsSourceBucketName + "/"
                                 + PluginPropertyUtils.pluginProp(path));
  }

  @Then("Toggle GCS source property skip header to true")
  public void toggleGCSSourcePropertySkipHeaderToTrue() {
    CdfGcsActions.skipHeader();
  }

  @Then("Enter GCS source property path field {string}")
  public void enterGCSSourcePropertyPathField(String pathField) {
    CdfGcsActions.enterPathField(PluginPropertyUtils.pluginProp(pathField));
  }

  @Then("Enter GCS source property override field {string} and data type {string}")
  public void enterGCSSourcePropertyOverrideFieldAndDataType(String overrideField, String dataType) {
    CdfGcsActions.enterOverride(PluginPropertyUtils.pluginProp(overrideField));
    CdfGcsActions.clickOverrideDataType(PluginPropertyUtils.pluginProp(dataType));
  }

  @Then("Enter GCS source property minimum split size {string} and maximum split size {string}")
  public void enterGCSSourcePropertyMinimumSplitSizeAndMaximumSplitSize(String minSplitSize, String maxSplitSize) {
    CdfGcsActions.enterMaxSplitSize(PluginPropertyUtils.pluginProp(minSplitSize));
    CdfGcsActions.enterMinSplitSize(PluginPropertyUtils.pluginProp(maxSplitSize));
  }

  @Then("Enter GCS source property regex path filter {string}")
  public void enterGCSSourcePropertyRegexPathFilter(String regexPathFilter) {
    CdfGcsActions.enterRegexPath(PluginPropertyUtils.pluginProp(regexPathFilter));
  }

  @Then("Enter the GCS source mandatory properties")
  public void enterTheGCSSourceMandatoryProperties() throws InterruptedException, IOException {
    CdfGcsActions.enterReferenceName();
    CdfGcsActions.enterProjectId();
    CdfGcsActions.getGcsBucket("gs://" + TestSetupHooks.gcsSourceBucketName + "/"
                                 + PluginPropertyUtils.pluginProp("gcsCsvFile"));
    CdfGcsActions.selectFormat("csv");
    CdfGcsActions.skipHeader();
    CdfGcsActions.getSchema();
    SeleniumHelper.waitElementIsVisible(CdfGCSLocators.getSchemaLoadComplete, 20L);
  }
}
