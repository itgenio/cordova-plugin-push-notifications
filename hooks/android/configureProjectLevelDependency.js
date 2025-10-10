const fs = require('fs')
const path = require('path')

function addProjectLevelDependency(platformRoot) {
  return new Promise((resolve, reject) => {
    try {
      const lib = 'com.google.gms:google-services:4.4.2'
      const dependency = `classpath '${lib}'`
    
      const projectBuildFile = path.join(platformRoot, 'build.gradle')
    
      let fileContents = fs.readFileSync(projectBuildFile, 'utf8')
    
      const findClassPath = new RegExp(/\bclasspath\b.*/, 'g')
      let matchClassPath = findClassPath.exec(fileContents)
      if (matchClassPath !== null) {
        const checkExistDependency = new RegExp(dependency.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g')
        let checkMatch = checkExistDependency.exec(fileContents)
        if (checkMatch !== null) {
          console.log(`Dependency ${dependency} already exist`)
        } else {
          let insertLocation = matchClassPath.index + matchClassPath[0].length
          fileContents = fileContents.substring(0, insertLocation) + '\n\t\t' + dependency + fileContents.substring(insertLocation)
    
          fs.writeFileSync(projectBuildFile, fileContents, 'utf8')
          console.log(`Updated ${projectBuildFile} to include dependency ${dependency}`)
        }
      } else {
        console.error(`Unable to insert dependency ${dependency}`)
      }
      resolve()
    } catch (err) {
      reject(err)
    }
  })
}

function ensureKotlinVersion(platformRoot) {
  return new Promise((resolve, reject) => {
    try {
      const projectBuildFile = path.join(platformRoot, 'build.gradle')
      let fileContents = fs.readFileSync(projectBuildFile, 'utf8')
      
      // Check for Kotlin version
      const kotlinVersionRegex = /ext\.kotlin_version\s*=\s*['"]([^'"]+)['"]/
      const match = kotlinVersionRegex.exec(fileContents)
      
      if (match) {
        const currentVersion = match[1]
        const requiredVersion = '1.9.24'
        
        if (currentVersion !== requiredVersion) {
          fileContents = fileContents.replace(
            kotlinVersionRegex,
            `ext.kotlin_version = '${requiredVersion}'`
          )
          fs.writeFileSync(projectBuildFile, fileContents, 'utf8')
          console.log(`Updated Kotlin version from ${currentVersion} to ${requiredVersion}`)
        } else {
          console.log(`Kotlin version ${requiredVersion} already set`)
        }
      }
      
      resolve()
    } catch (err) {
      reject(err)
    }
  })
}

module.exports = async (context) => {
  'use strict'
  const platformRoot = path.join(context.opts.projectRoot, 'platforms/android')

  await addProjectLevelDependency(platformRoot)
    .catch((err) => {
      console.error(err.message)
    })
    
  await ensureKotlinVersion(platformRoot)
    .catch((err) => {
      console.error(err.message)
    })
}