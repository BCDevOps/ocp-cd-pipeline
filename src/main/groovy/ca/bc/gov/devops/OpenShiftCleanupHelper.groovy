
package ca.bc.gov.devops

import ca.bc.gov.devops.OpenShiftHelper

class OpenShiftCleanupHelper extends OpenShiftHelper{
    def config

    enum ConfigType {
        BUILD('build'),
        DEPLOYMENT('deployment')

        ConfigType(String value){
            this.value = value
        }

        private final String value

        public String getValue(){
            return value
        }
    }

    public OpenShiftCleanupHelper(config){
        this.config=config
    }

    /*
     * Removes tags related to the build specified in the configuration from shared image streams
     * that are not "base-images" (e.g.: python base image for rhel7)
     */
    private void removeTagsFromSharedImageStreams(String namespace){
        Map ret = ocGet(['is', '-l', "app-name=${config.app.name}", '-n', "${namespace}"])
        for(imageStream in ret.items){
            oc(['delete', 'istag', "${imageStream.metadata.name}:${config.app.build.name}", '--ignore-not-found=true', '-n', "${namespace}"])
        }
    }

    /*
     * Removes all the objects created by the build config provided by the configuration,
     * in the specified namespace/project
     */
    private void cleanUpBuild(String namespace){
        println 'Removing all objects created by the buildConfig...'

        // deletes all resources labelled as app-env=mylabel
        oc(['delete', 'all', '-l', "env-name=${config.app.name}-${config.app.changeId}", '-n', "${namespace}"])

        // removes tags in shared imagestreams corresponfing to the build
        removeTagsFromSharedImageStreams(namespace)
    }

    /*
     * Removes all the objects created by the deployment config provided by the configuration,
     * in the specified namespace/project
     */
    private void cleanUpDeployment(String namespace){
        println 'Removing all objects created by the deploymentConfig...'

        // deletes all resources labelled with app-env=mylabel
        oc(['delete', 'all', '-l', "env-name=${config.app.deployment.name}", '-n', "${namespace}"])

        // removes secret, configmap, pvc labelled with app-env=mylabel
        oc(['delete', 'secret,configmap,pvc', '-l', "env-name=${config.app.deployment.name}", '-n', "${namespace}"])

        // TODO: purge istags older than last 5
    }

    public void cleanup(){
        java.time.Instant startInstant = java.time.Instant.now()
        java.time.Duration duration = java.time.Duration.ZERO

        println 'Starting cleanup process...'

        // clean up teh deployment first...
        cleanUpDeployment(config.app.deployment.namespace)
        
        // ...and then remove the build
        cleanUpBuild(config.app.build.namespace)

        println 'Cleanup process completed'

        duration = java.time.Duration.between(startInstant, java.time.Instant.now())
        println "Elapsed Seconds: ${duration.getSeconds()} (max = ${config.app.build.timeoutInSeconds})"
    }
}