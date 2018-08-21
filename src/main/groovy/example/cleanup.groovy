package example

import ca.bc.gov.devops.OpenShiftHelper
import groovy.transform.BaseScript
import ca.bc.gov.devops.OpenShiftCleanupHelper

@BaseScript ca.bc.gov.devops.Base _super

def config = OpenShiftHelper.loadDeploymentConfig()

new OpenShiftCleanupHelper(config).cleanup()

println 'Done!!'
