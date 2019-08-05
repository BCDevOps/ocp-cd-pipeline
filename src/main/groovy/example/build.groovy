package example

import groovy.transform.BaseScript
import ca.bc.gov.devops.OpenShiftBuildHelper

@BaseScript ca.bc.gov.devops.BasicBuild _super

@groovy.transform.SourceURI URI sourceURI

println 'sourceURI:'
println sourceURI

runScript(sourceURI)

println 'Done!!'
