package ca.bc.gov.devops

import groovy.cli.picocli.CliBuilder
import groovy.cli.picocli.OptionAccessor

import java.nio.file.Paths

abstract class BasicDeploy extends Script {
    //abstract def runScript()
    CliBuilder cli
    OptionAccessor opt

    static public CliBuilder withOptions(CliBuilder _cli){
        _cli.with {
            h(longOpt: 'help', 'Show usage information')
            n(longOpt: 'name', args: 1, argName: 'Name', 'Name', required: false)
            c(longOpt: 'config', args: 1, argName: 'Pipeline config file', 'Pipeline config file', required: true)
            e(longOpt: 'env', args: 1, argName: 'Target environment name', 'Target environment name', required: true)
            _(longOpt: 'deployment-name', args: 1, argName: 'Deployment Name', 'Deployment Name', required: false)
            _(longOpt: 'build-name', args: 1, argName: 'Deployment Name', 'Deployment Name', required: false)
            _(longOpt: 'pr', args: 1, argName: 'Pull Request Number', 'GitHub Pull Request #', required: true)
        }
        return _cli
    }

    def runScript(URI scriptSourceUri) {
        File scriptSourceFile = Paths.get(scriptSourceUri).toFile()

        cli = new CliBuilder(usage: "groovy ${scriptSourceFile.getName()} --pr=<pull request#> --config=<path> --env=<name>")

        opt = withOptions(cli).parse(args)


        if (opt == null) {
            //System.err << 'Error while parsing command-line options.\n'
            //cli.usage()
            System.exit 2
        }

        if (opt?.h) {
            cli.usage()
            return 0
        }

        def config = OpenShiftHelper.loadDeploymentConfig(opt)
        //println "${config}"
        //System.exit(1)
        //println config
        //TODO:Verify access to the project/namespace
        //system:serviceaccount:empr-mds-tools:jenkins
        //println "oc policy add-role-to-user edit `oc whoami` -n ${config.app.deployment.namespace}"
        new OpenShiftDeploymentHelper(config).deploy()

    }
}