import groovy.transform.BaseScript
import ca.bc.gov.devops.OpenShiftDeploymentHelper

@BaseScript ca.bc.gov.devops.BasicDeploy _super

@groovy.transform.SourceURI URI sourceURI

runScript(sourceURI)

println 'Done!!'
