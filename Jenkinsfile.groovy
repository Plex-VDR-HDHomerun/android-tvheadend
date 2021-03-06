import jenkins.model.*

def getAppVersionCode() {
    def appVersionCode = jenkins.model.Jenkins.instance.getItem("android-tvheadend-version-code").nextBuildNumber
    build job: 'android-tvheadend-version-code'
    return appVersionCode
}

def assemble() {
    sh './gradlew assemble'
}

def archive() {
    archiveArtifacts artifacts: 'app/build/outputs/apk/*.apk', fingerprint: true
    stash includes: 'app/build/outputs/apk/*.apk', name: 'built-apk'
}

def lint() {
    sh './gradlew lint'
    androidLint canComputeNew: false, canRunOnFailed: true, defaultEncoding: '', healthy: '', pattern: '**/lint-results*.xml', unHealthy: ''
}

def publishApkToStore(String trackName) {
    def changeLog = sh(returnStdout: true, script: "./tools/generate-changelog").trim()

    androidApkUpload(
        apkFilesPattern: 'app/build/outputs/apk/ie.macinnes.tvheadend_*-release.apk',
        googleCredentialsId: 'android-tvheadend',
        trackName: trackName,
        recentChangeList: [
            [language: 'en-GB', text: changeLog],
        ],
    )
}

return this;
