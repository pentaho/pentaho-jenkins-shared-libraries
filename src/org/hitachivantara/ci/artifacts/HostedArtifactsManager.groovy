/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.artifacts

import com.cloudbees.groovy.cps.NonCPS
import org.hitachivantara.ci.FileUtils
import org.hitachivantara.ci.config.BuildData

import java.nio.file.Paths
import java.text.DecimalFormat
import java.time.LocalDateTime

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
    String hostedRoot = getHostedRoot(this.buildData)
    if (!hostedRoot) {
      dsl.log.warn "Not able to determine where to create hosted page!"

    } else {
      final String deployCredentials = buildData.getString(ARTIFACT_DEPLOYER_CREDENTIALS_ID)
      final String artifactoryURL = buildData.getString('ARTIFACTORY_BASE_API_URL')

      def credential = lookupSystemCredentials(deployCredentials)
      final String user = credential.getUsername()
      final String password = credential.getPassword().getPlainText()

      Artifactory artifactoryHandler = new Artifactory(dsl, artifactoryURL, user, password)
      final String releaseVersion = buildData.getString(RELEASE_VERSION)
      final String header = dsl.libraryResource resource: "templates/hosted/header", encoding: 'UTF-8'
      StringBuilder content = new StringBuilder(header)

      if (isSnapshotBuild()) {
        buildIndexHtml(releaseVersion, null, hostedRoot, content, artifactoryHandler)
      } else {
        List<Map> versionsData = artifactoryHandler
            .searchArtifacts(
                ["pentaho-parent-pom-${releaseVersion}-*.pom"],
                "$releaseVersion-*",
                "$releaseVersion-SNAPSHOT",
                "\$desc",
                4
            )

        List<String> relevantBuildNbrs = versionsData.collect {
          (it.name as String)
              .replace("pentaho-parent-pom-${releaseVersion}-", "")
              .replace(".pom", "")
        }

        try {
          List<String> menuIds = relevantBuildNbrs.collect {
            "$releaseVersion-" << (isSnapshotBuild() ? "SNAPSHOT" : it)
          } as List<String>

          content.append(createMenus(menuIds))

          boolean isLatest = true
          for (String buildNbr : relevantBuildNbrs) {
            String pathMatcher = "$releaseVersion-" << (isSnapshotBuild() ? "SNAPSHOT" : buildNbr)
            buildIndexHtml(releaseVersion, buildNbr, content, artifactoryHandler, pathMatcher, hostedRoot, isLatest)
            isLatest = false
          }
        } catch (Exception e) {
          dsl.log.error "$e"
        }
      }

      dsl.writeFile file: "${hostedRoot}/../index.html", text: "<div class=\"tabs\">${content.toString()}</div>"
    }
  }

  def buildIndexHtml(
      final String releaseVersion,
      final String buildNbr,
      StringBuilder content,
      Artifactory artifactoryHandler,
      String pathMatcher,
      String hostedRoot,
      Boolean isLatest
  ) {
    List<Map> artifactsMetadata = artifactoryHandler.searchArtifacts(getFileNames(buildNbr), pathMatcher)

    if (isSnapshotBuild()) {
      artifactsMetadata = getLatestArtifacts(artifactsMetadata)
    }

    if (artifactsMetadata?.size() > 0) {
      String htmlSection = createHtmlSection(artifactsMetadata, pathMatcher)
      content.append(htmlSection)

      /*
        When iterating the builds, when we find the current build, we need to:
          - create the sum files in the proper path
          - create the index in the 'latest' folder
      */
      if (isLatest) {
        // create the checksum files
        for(Map file : artifactsMetadata) {
          dsl.writeFile file: "$hostedRoot/${file.name}.sum", text: "SHA1=${file.actual_sha1}"
        }

        // create the index in the 'latest' folder
        dsl.writeFile file: "$hostedRoot/../latest/index.html", text: content.toString()
      }

    } else {
      dsl.log.info "No artifacts were found in Artifactory for build $buildNbr!"
    }
  }

  @NonCPS
  static List getLatestArtifacts(List<Map> artifactsMetadata) {
    Map<String, Map> latestArtifacts = [:]
    artifactsMetadata.forEach {
      String name = it.name as String
      name = name.replaceAll("[\\d.]", "");
      latestArtifacts.put(name, it)
    }

    return latestArtifacts.collect { key, value ->
      value
    }.sort { a, b -> a.name <=> b.name }

  }

  boolean isSnapshotBuild() {
    return buildData.getBool(IS_SNAPSHOT)
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

  String createMenus(List<String> relevantBuildNbrs) {
    String template = dsl.libraryResource resource: "templates/hosted/buildsMenu.vm", encoding: 'UTF-8'

    Map bindings = [
        builds: relevantBuildNbrs
    ]

    String index

    try {
      index = dsl.resolveTemplate(
          text: template,
          parameters: bindings
      )
    } catch (Exception e) {
      dsl.log.error "$e"
    }

    return index
  }

  String createHtmlSection(List<Map> artifactsMetadata, String version) {
    String template = dsl.libraryResource resource: "templates/hosted/artifacts.vm", encoding: 'UTF-8'
    String currentDate = String.format('%tF %<tH:%<tM', LocalDateTime.now())

    Map bindings = [
        files          : artifactsMetadata,
        buildHeaderInfo: "Build ${version} | ${currentDate}",
        artifatoryURL  : buildData.getString('MAVEN_RESOLVE_REPO_URL'),
        numberFormat   : new DecimalFormat("###,##0.000"),
        version        : version
    ]

    String index

    try {
      index = dsl.resolveTemplate(
          text: template,
          parameters: bindings
      )
    } catch (Exception e) {
      dsl.log.error "$e"
    }

    return index
  }

  List<String> getFileNames(String buildNbr = null) {
    Map props = loadVersionsFromFile()

    props << buildData.buildProperties.collectEntries { k, v ->
      [(k): v ?: '']
    }

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
          properVersion = properVersion
              .replace('BUILDTAG', isSnapshotBuild() ? '*' : buildNbr)
              .replace('SNAPSHOT', '*')
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
