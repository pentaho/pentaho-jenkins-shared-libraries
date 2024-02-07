/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.artifacts

import org.hitachivantara.ci.FileUtils
import org.hitachivantara.ci.config.BuildData

import java.nio.file.Paths

import static org.hitachivantara.ci.config.LibraryProperties.*

import com.cloudbees.plugins.credentials.CredentialsMatchers
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials
import com.cloudbees.plugins.credentials.domains.DomainRequirement
import jenkins.model.Jenkins
import hudson.security.ACL

class HostedArtifactsManager implements Serializable {

  Script dsl
  BuildData buildData

  HostedArtifactsManager(Script dsl, BuildData buildData) {
    this.dsl = dsl
    this.buildData = buildData
  }

  /**
   * Reads the artifacts manifest list either from a
   * manifest file, a Map or a List
   *
   * @return list of the files names
   */
  List<String> getArtifactsNames() {
    List<String> fileNames = []
    def manifestConfig = buildData.get(ARCHIVING_CONFIG)

    if (manifestConfig in CharSequence) {

      def manifest

      // tries to load the string as a valid yaml
      manifest = dsl.readYaml(text: manifestConfig)

      if (manifest in String) {
        dsl.log.warn "Could not read ${manifestConfig} as a valid yaml structure. Trying as a file path..."

        if (!FileUtils.exists(manifestConfig)) {
          throw new ManifestNotFoundException("Artifact archiving is misconfigured. '${manifestConfig}' is not a valid manifest file path!")
        }

        manifest = dsl.readYaml(file: manifestConfig)
        dsl.log.debug "Read manifest from ${manifestConfig}", manifest
      }

      if (!manifest) {
        dsl.log.warn "Manifest is empty"
      } else {
        fileNames = handleManifestConfig(manifest)
      }
    } else {
      fileNames = handleManifestConfig(manifestConfig)
    }

    return fileNames
  }

  /**
   * Checks and handles all of the possible content structure
   * @param manifest generic content
   * @return
   */
  List<String> handleManifestConfig(def manifest) {
    List<String> fileNames = []

    if (manifest in Map) {
      manifest.values().each {
        fileNames.addAll(handleManifestConfig(it))
      }

    } else if (manifest in List) {
      fileNames.addAll(manifest)
    }

    return fileNames?.unique()
  }

/**
 * Looks for the artifacts and does the actual archiving
 */
  void hostArtifacts() {
    dsl.log.info "************** hostArtifacts"
    String hostedRoot = getHostedRoot(this.buildData)
    if (!hostedRoot) {
      dsl.log.warn "Not able to determine where to create hosted page!"

    } else {
      String deployCredentials = buildData.getString(ARTIFACT_DEPLOYER_CREDENTIALS_ID)
      String artifactoryURL = buildData.getString('ARTIFACTORY_BASE_API_URL')
      dsl.log.info "*** $artifactoryURL"
      
      List<Map> artifactsMetadata = []
      dsl.log.info "************** $artifactoryURL"
      dsl.log.info "************** $deployCredentials"
      String user = "cardoso"
      try {
        def credential = lookupSystemCredentials(deployCredentials)
        user = credential.getUsername()
        String password = credential.getPassword().getPlainText()

        artifactsMetadata = new Artifactory(dsl, artifactoryURL, user, password, "").searchArtifacts(getFileNames())

      } catch (Exception e) {
        dsl.log.info "9999999999 $e"
      }


      dsl.log.info "9999999999 $user"
      //artifactsMetadata = new Artifactory(artifactoryURL, user, password, "" ).searchArtifacts(getFileNames())

      /* dsl.withCredentials([steps.usernamePassword(credentialsId: deployCredentials,
           usernameVariable: 'RT_USER', passwordVariable: 'RT_PASSWORD')]) {
         dsl.log.info "************** vitor"
         dsl.log.info "************** \$RT_USER"
         //artifactsMetadata = new Artifactory(artifactoryURL, "\$RT_USER", "\$RT_PASSWORD", "" ).searchArtifacts(getFileNames())
       }*/

      if (artifactsMetadata?.size() > 0) {
        createHtmlIndex(artifactsMetadata, hostedRoot)
      } else {
        dsl.log.info "No artifacts were found in Artifactory!"
      }
    }
  }

  def lookupSystemCredentials = { credentialsId ->
    def jenkins = Jenkins.get()
    return CredentialsMatchers.firstOrNull(
        CredentialsProvider
            .lookupCredentials(StandardUsernameCredentials.class, jenkins, ACL.SYSTEM,
                Collections.<DomainRequirement> emptyList()),
        CredentialsMatchers.withId(credentialsId)
    )
  }

  def createHtmlIndex(List<Map> artifactsMetadata, String hostedRootFolder) {
    String template = dsl.libraryResource resource: "templates/hosted/artifacts.vm", encoding: 'UTF-8'

    bindings = [
        files: artifactsMetadata
    ]

    def index = dsl.resolveTemplate(
        text: template,
        parameters: bindings
    )

    def cmd = "echo ${index} >> ${hostedRootFolder}/index.html"
    dsl.sh(cmd)
  }

  List<String> getFileNames() {
    dsl.log.info("111111111")
    Map props = loadVersionsFromFile()
    dsl.log.info("2222222222")
    props << buildData.buildProperties.collectEntries { k, v ->
      [(k): v ?: '']
    }
    dsl.log.info("3333333")
    return getArtifactsNames().collect {
      it.replaceAll(/\$\{(.*?)\}/) { m, k ->

        String properVersion
        //jenkins sandbox gives different results than regular groovy
        if (k) {
          properVersion = props.get(k)
        } else if (m) {
          properVersion = props.get(m[1])
        }

        if (properVersion) {
          properVersion = properVersion.replace('BUILDTAG', buildData.getString(RELEASE_BUILD_NUMBER))
        }

        return properVersion
      }
    }

  }

  Map loadVersionsFromFile() {
    String propsFile = "${buildData.getString(BUILD_CONFIG_ROOT_PATH)}/${buildData.getString(BUILD_VERSIONS_FILE)}"

    if (!FileUtils.exists(propsFile)) {
      propsFile = Paths.get(buildData.getString(BUILD_CONFIG_ROOT_PATH), 'version.properties') as String
    }

    Properties properties = new Properties()
    properties.load(new StringReader(dsl.readFile(file: propsFile, encoding: 'UTF-8') as String))

    return properties.collectEntries { k, v ->
      [(k): v]
    }
  }


  /**
   * Retrieves the target path on hosted
   * @return
   */
  static String getHostedRoot(BuildData buildData) {

    if (!buildData.isSet(BUILD_HOSTING_ROOT)) {
      return ''
    }

    return Paths.get(
        buildData.getString(BUILD_HOSTING_ROOT).trim(),
        buildData.getString(DEPLOYMENT_FOLDER).trim(),
        buildData.getString(RELEASE_BUILD_NUMBER).trim()
    ) as String
  }

}