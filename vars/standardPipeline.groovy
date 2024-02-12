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
}