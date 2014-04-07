/*
 * liftup
 *
 * Copyright 2014  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.liftup

import org.gradle.api.*
import org.gradle.api.plugins.*
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.*

/**
 *
 * @author akhikhl
 */
class TaskUtils {

  static void defineEclipseBundleTasks(Project project) {

    File manifestFile = new File("${project.buildDir}/bundleManifest/MANIFEST.MF")

    project.task('createBundleManifest') {
      dependsOn project.tasks.classes
      inputs.files { project.configurations.runtime }
      outputs.files manifestFile
      doLast {
        // fix problem with non-existing classesDir, when the project contains no java/groovy sources
        // (resources-only project)
        project.sourceSets.main.output.classesDir.mkdirs()

        def m = project.osgiManifest {
          setName project.name
          setVersion project.version
          setClassesDir project.sourceSets.main.output.classesDir
          setClasspath (project.configurations.runtime - project.configurations.privateLib)
        }

        m = m.effectiveManifest

        String activator = ProjectUtils.findBundleActivator(project)
        if(activator) {
          m.attributes['Bundle-Activator'] = activator
          m.attributes['Bundle-ActivationPolicy'] = 'lazy'
        }

        def pluginConfig = ProjectUtils.findPluginConfig(project)

        if(pluginConfig) {
          m.attributes['Bundle-SymbolicName'] = "${project.name}; singleton:=true" as String
          Map importPackages = ProjectUtils.findImportPackagesInPluginConfigFile(project, pluginConfig).collectEntries { [ it, '' ] }
          importPackages << ManifestUtils.parsePackages(m.attributes['Import-Package'])
          m.attributes['Import-Package'] = ManifestUtils.packagesToString(importPackages)
        }
        else
          m.attributes['Bundle-SymbolicName'] = project.name

        def localizationFiles = ProjectUtils.collectPluginLocalizationFiles(project)
        if(localizationFiles)
          m.attributes['Bundle-Localization'] = 'plugin'

        if(project.configurations.privateLib.files) {
          Map importPackages = ManifestUtils.parsePackages(m.attributes['Import-Package'])
          ProjectUtils.collectPrivateLibPackages(project).each { privatePackage ->
            def packageValue = importPackages.remove(privatePackage)
            if(packageValue != null) {
              project.logger.info 'Package {} is referenced by private library, will be excluded from Import-Package.', privatePackage
              importPackages['!' + privatePackage] = packageValue
            }
          }
          m.attributes['Import-Package'] = ManifestUtils.packagesToString(importPackages)
        }

        def requiredBundles = [ 'org.eclipse.core.runtime', 'org.eclipse.core.resources' ] as LinkedHashSet
        if(pluginConfig && pluginConfig.extension.find { it.'@point'.startsWith 'org.eclipse.core.expressions' })
          requiredBundles.add 'org.eclipse.core.expressions'
        project.configurations.compile.allDependencies.each {
          if(it.name.startsWith('org.eclipse.') && !PlatformConfig.isPlatformFragment(it) && !PlatformConfig.isLanguageFragment(it))
            requiredBundles.add it.name
        }
        m.attributes 'Require-Bundle': requiredBundles.sort().join(',')

        def bundleClasspath = m.attributes['Bundle-Classpath']
        if(bundleClasspath)
          bundleClasspath = bundleClasspath.split(',\\s*').collect()
        else
          bundleClasspath = []

        bundleClasspath.add(0, '.')

        project.configurations.privateLib.files.each {
          bundleClasspath.add(it.name)
        }

        bundleClasspath.unique(true)

        m.attributes['Bundle-Classpath'] = bundleClasspath.join(',')

        manifestFile.parentFile.mkdirs()
        manifestFile.withWriter { m.writeTo it }
      } // doLast
    } // createBundleManifest task

    project.jar {
      dependsOn project.tasks.createBundleManifest
      inputs.files project.files(manifestFile)
      from { project.configurations.privateLib }
      manifest {
        from(manifestFile.absolutePath) {
          eachEntry { details ->
            def newValue
            if(details.key == 'Require-Bundle')
              newValue = ManifestUtils.mergeRequireBundle(details.baseValue, details.mergeValue)
            else if(details.key == 'Import-Package' || details.key == 'Export-Package')
              newValue = ManifestUtils.mergePackageList(details.baseValue, details.mergeValue)
            else
              newValue = details.mergeValue ?: details.baseValue
            if(newValue)
              details.value = newValue
            else
              details.exclude()
          }
        }
      }
    } // jar task

  } // defineEclipseBundleTasks
}