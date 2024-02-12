/* groovylint-disable LineLength, NestedBlockDepth */
import net.sf.json.JSONArray
import net.sf.json.JSONObject
/* groovylint-disable-next-line NoWildcardImports */
import hudson.model.*
/* groovylint-disable-next-line ConstantsOnlyInterface */
interface ApplicationConstants {
    enum ModeType {
        PRODUCTIONREADY(9), 
        DEVELOP_BRANCH_BUILD(2), 
        FEATURE_BRANCH_BUILD(3), 
        HOTFIX_BRANCH_BUILD(4),
        MR_SCAN(5)

        ModeType(int value) {
            this.value = value
        }
        private final int value
        int getValue() {
            return value
        }
    }

    static final String ABORTED = 'ABORTED'
    static final String RELEASE = 'release/'
    static final int ZERO = 0
    static final String NEWLINE = '\n'
    static final String MASTER = 'master'
    static final int SECONDSINMS = 1000
    static final String ZEROSTRING = '0'
}


void call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    // Default mode is 3 (meaning feature branch)
    // Just build and publish docker, build nugets but not publish them
    String report = '\n\n# Build (and deploy) Report \n\n'
    ApplicationConstants.ModeType mode = ApplicationConstants.ModeType.FEATURE_BRANCH_BUILD
    String branch = env.BRANCH_NAME
    echo 'Pulling... Branch : ' + branch

    String branchType = ''

    // Get the merge request ID
    def mergeRequestId = env.gitlabMergeRequestId
    echo 'gitlabMergeRequestId... ' + mergeRequestId

    if (config.docker_targets == null) {
        echo 'config is empty'
        config.docker_targets = []
    } else {
        def docker_target_size = config.docker_targets.size()
        echo 'docker target size ' + docker_target_size
    }

    node {
         stage('Build Mode') {
            echo 'Build Mode started'
         }

         stage('Version') {
            echo 'Version started'
            // added to solve the issue of jenkins building stale commits
            step([$class: 'WsCleanup'])

             //    checkout scm
            checkout([
                    $class: 'GitSCM',
                    branches: scm.branches,
                    doGenerateSubmoduleConfigurations: scm.doGenerateSubmoduleConfigurations,
                    extensions: scm.extensions + [[$class: 'CloneOption', noTags: false, reference: '', shallow: false], [$class: 'CleanCheckout']],
                    submoduleCfg: [],
                    userRemoteConfigs: scm.userRemoteConfigs,
            ])

            repositoryName = scm.getUserRemoteConfigs()[0].getUrl().tokenize('/')[-1].replace('.git', '')
            sonarProjectKey = "MA-${repositoryName}"
            echo "Repository name: ${repositoryName}"

            (commitId, version, versionSuffix, dockerImageTag, buildPropsExists) = getVersionInformation(branch, branchType, mode)

            versionWithSuffix = version
            if (versionSuffix != null && versionSuffix != ''){
                versionWithSuffix = versionWithSuffix + '-' + versionSuffix
            }
            echo "commitId:${commitId}\ndockerImageTag: ${dockerImageTag}\n versionSuffix:${versionSuffix}\nversion:${version}"

         }
    }
}

/* groovylint-disable-next-line MethodReturnTypeRequired, NoDef */
def getVersionInformation(String branch, String branchType, ApplicationConstants.ModeType mode) {

    String buildPropsVersion = ''
    boolean buildPropsExists = false
    boolean packageJsonExists = false
    String packageJsonVersion = ''

    // populate variables from pulled code
    if (fileExists('Directory.Build.props')) {
        buildPropsExists = true
        // get version from Directory.Build.props
        buildPropsVersion = sh(script: "grep '<.*VersionPrefix.*>[0-9].*</.*VersionPrefix>' Directory.Build.props | cut -f2 -d'>' | cut -f1 -d'<' ", returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '').trim()
    }

    String packageJsonCountStr = sh(script: 'find . -name package.json|grep -v node_modules | wc -l', returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '').trim()
    //String netStandard21CountStr = sh(script: "find . -type f -name '*.csproj' | xargs grep netstandard2.1 |wc -l", returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '').trim()

    /* groovylint-disable-next-line UnnecessaryGetter */
    int packageJsonCount = packageJsonCountStr.isInteger() ? packageJsonCountStr.toInteger() : 0
    /* groovylint-disable-next-line UnnecessaryGetter */
    //int netStandard21Count = netStandard21CountStr.isInteger() ? netStandard21CountStr.toInteger() : 0

    if (packageJsonCount == ApplicationConstants.ZERO) {
        packageJsonExists = false
    } else if (packageJsonCount == 1) {
        packageJsonExists = true
        // grep does not print the file name so field 2
        // Trying with awk to extract correct version number.
        packageJsonVersion = sh(script: "cat \$(find . -name package.json|grep -v node_modules) | grep version | head -1 | awk -F: '{ print \$2 }' | sed 's/[\", ]//g'", returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '').trim()
    } else {
        packageJsonExists = true
        // grep does not print the file name so field 3
        packageJsonVersion = sh(script: "find . -name package.json|grep -v node_modules |xargs grep \"version\".*:|cut -f3 -d:|sed 's/\"//g'|sed 's/,//g'|sort -unr | head -1", returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '').trim()
    }

    // String gitLatestTag = ''
    String gitHeadTag = sh(script: 'git tag --points-at HEAD --sort=-v:refname | grep ^v | head -1 | cut -c2- ', returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '')

    echo "buildPropsExists:${buildPropsExists} \nbuildPropsVersion:${buildPropsVersion}\nPackage.json Count:${packageJsonCount}\npackageJsonCountStr:${packageJsonCountStr}"

    validate(gitHeadTag, buildPropsExists,
        packageJsonExists, buildPropsVersion, packageJsonVersion, mode)

    commitId = sh(script: 'git rev-parse HEAD', returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '')

    version = getVersion(branch, gitHeadTag,
        buildPropsExists, packageJsonExists,
        buildPropsVersion, packageJsonVersion)

    // versionSuffix = getVersionSuffix(gitHeadTag, branchType, mode)
    // dockerImageTag = getDockerImageTag(version, versionSuffix,branchType)

    // if (mode == ApplicationConstants.ModeType.PRODUCTIONREADY && (gitHeadTag == null || gitHeadTag == '')) {
    //     report = "${report}## mode: `Master build (un-tagged build)`\n"
    //     currentBuild.result = ApplicationConstants.ABORTED
    //     error('Aborting: Master build is un-tagged')
    // }

    // withCredentials([string(credentialsId: "MA-SonarQubeToken", variable: 'SONARQUBE_KEY')]) {
    //     if (buildPropsExists) {
    //         writeFile(file: 'src/NuGet.Config', text: "<?xml version=\"1.0\" encoding=\"utf-8\"?> <configuration> <packageSources> <add key=\"kuber\" value=\"${env.NUGETFORRESTORE}\" /> </packageSources> <packageSourceCredentials> <kuber> <add key=\"Username\" value=\"${env.NUGETFORRESTORE_USER}\" /> <add key=\"ClearTextPassword\" value=\"${env.NUGETFORRESTORE_PASS}\" /> </kuber> </packageSourceCredentials> </configuration>")
    //         writeFile(file: 'src/SonarQube.Analysis.xml', text: "<SonarQubeAnalysisProperties  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns=\"http://www.sonarsource.com/msbuild/integration/2015/1\"><Property Name=\"sonar.host.url\">${env.SONARQUBE_URL}</Property><Property Name=\"sonar.login\">${env.SONARQUBE_KEY}</Property><Property Name=\"sonar.token\">${env.SONARQUBE_KEY}</Property><Property Name=\"sonar.qualitygate.wait\">false</Property></SonarQubeAnalysisProperties>")
    //     }
    //     else{
    //         writeFile(file: 'src/sonar-project.properties', text: "sonar.host.url=${env.SONARQUBE_URL}\nsonar.login=${env.SONARQUBE_KEY}\nsonar.qualitygate.wait=false")        
    //     }
    // }


    // return [commitId, version, versionSuffix, dockerImageTag, buildPropsExists]

    return [commitId, version, "v", "t-image", buildPropsExists]
}

// This is to validate the code base before proceeding with build steps.
// In the interest of performance, avoid xml and json parsing and instead rely on text extraction even though it may not be rigorously accurate
/* groovylint-disable-next-line ParameterCount */
void validate(String gitHeadTag, Boolean buildPropsExists,
             Boolean packageJsonExists, String buildPropsVersion, String packageJsonVersion, ApplicationConstants.ModeType mode) {
    String errorMessage = ''
    boolean isInvalid = false

    // Directory.Build.props OR package.json MUST exist
    if (!buildPropsExists && !packageJsonExists) {
        isInvalid = true
        errorMessage = errorMessage + 'Found neither Directory.Build.props nor package.json. Aborting.'
    }

    // For hybrid repositories, Directory.Build.props and package.json versions must match
    if (!isInvalid && buildPropsExists && packageJsonExists && buildPropsVersion != packageJsonVersion) {
        isInvalid = true
        errorMessage = errorMessage + 'Mismatch between Directory.Build.props version (' + buildPropsVersion + ') and package.json version (' + packageJsonVersion + '). Aborting.'
    }

    // Directory.Build.props, if present, MUST contain a value for VersionPrefix
    if (!isInvalid && buildPropsExists && buildPropsVersion == '') {
        isInvalid = true
        errorMessage = errorMessage + 'Directory.Build.props does not contain VersionPrefix. Aborting.'
    }

    // Directory.Build.props, if present, MUST NOT contain a value for VersionSuffix
    if (!isInvalid && buildPropsExists &&
            ApplicationConstants.ZEROSTRING != sh(script: "grep '<.*VersionSuffix.*>[0-9].*</.*VersionSuffix>' Directory.Build.props | wc -l", returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '')
    ) {
        isInvalid = true
        errorMessage = errorMessage + 'Directory.Build.props MUST NOT contain a value for VersionSuffix. Aborting.'
    }

    // VersionPrefix and VersionSuffix must NOT be present in individual csproj files
    if (!isInvalid && buildPropsExists && ApplicationConstants.ZEROSTRING != sh(script: 'find . -type f -name *.csproj|xargs grep -l -e VersionPrefix -e VersionSuffix|wc -l', returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '')) {
        isInvalid = true
        errorMessage = errorMessage + 'csproj file must not contain VersionPrefix or VersionSuffix. Aborting.'
    }

    // For master branch, if HEAD is tagged, and Directory.Build.props is present, HEAD tag MUST match VersionPrefix
    if (!isInvalid && mode == ApplicationConstants.ModeType.PRODUCTIONREADY && gitHeadTag != '' && buildPropsExists && gitHeadTag != buildPropsVersion) {
        isInvalid = true
        errorMessage = "${errorMessage} Mismatch between git HEAD tag (${gitHeadTag}) and Directory.Build.props VersionPrefix (${buildPropsVersion}). Aborting."
    }

    // For master branch, if HEAD is tagged, and package.json is present, HEAD tag must match version
    if (!isInvalid && mode == ApplicationConstants.ModeType.PRODUCTIONREADY && gitHeadTag != '' && packageJsonExists && gitHeadTag != packageJsonVersion) {
        isInvalid = true
        errorMessage = "${errorMessage} Mismatch between git HEAD tag (${gitHeadTag}) and package.json version (${packageJsonVersion}). Aborting."
    }

    if (!isInvalid && mode == ApplicationConstants.ModeType.PRODUCTIONREADY && buildPropsExists) {
        if (ApplicationConstants.ZEROSTRING != sh(script: "find . -type f -name *.csproj|xargs grep '<PackageReference.*Include.*LendFoundry.*Version.*-.*/>'|wc -l", returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '')
                || ApplicationConstants.ZEROSTRING != sh(script: "find . -type f -name *.csproj|xargs grep '<PackageReference.*Include.*LMS.*Version.*-.*/>'|wc -l", returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '')
                || ApplicationConstants.ZEROSTRING != sh(script: "find . -type f -name *.csproj|xargs grep '<PackageReference.*Include.*CreditExchange.*Version.*-.*/>'|wc -l", returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '')
                || ApplicationConstants.ZEROSTRING != sh(script: "find . -type f -name *.csproj|xargs grep '<PackageReference.*Include.*Fc360.*Version.*-.*/>'|wc -l", returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '')
                || ApplicationConstants.ZEROSTRING != sh(script: "find . -type f -name *.csproj|xargs grep '<PackageReference.*Include.*Docitt.*Version.*-.*/>'|wc -l", returnStdout: true).toString().replace(ApplicationConstants.NEWLINE, '')
        ) {
            isInvalid = true
            errorMessage = errorMessage + 'The csproj files must not contain invalid /Project/PackageReference@Version values for master branch. Aborting.'
        }
    }

    if (isInvalid) {
        echo errorMessage
        currentBuild.result = ApplicationConstants.ABORTED
        fNotify(errorMessage)
        error(errorMessage)
    }
}

// It is expected that getVersion() would be called after validate()
/* groovylint-disable-next-line ParameterCount */
String getVersion(String branch, String gitHeadTag, Boolean buildPropsExists, Boolean packageJsonExists, String buildPropsVersion, String packageJsonVersion) {
    if (ApplicationConstants.MASTER == branch && gitHeadTag != '') {
        return gitHeadTag
    }
    if (ApplicationConstants.MASTER == branch && gitHeadTag == '' && buildPropsExists) {
        return buildPropsVersion
    }
    if (ApplicationConstants.MASTER == branch && gitHeadTag == '' && packageJsonExists) {
        return packageJsonVersion
    }
    if (ApplicationConstants.MASTER != branch && buildPropsExists) {
        return buildPropsVersion
    }
    if (ApplicationConstants.MASTER != branch && packageJsonExists) {
        return packageJsonVersion
    }
}