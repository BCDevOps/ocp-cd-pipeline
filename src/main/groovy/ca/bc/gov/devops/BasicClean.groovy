package ca.bc.gov.devops

import groovy.cli.picocli.CliBuilder
import groovy.cli.picocli.OptionAccessor

import java.nio.file.Paths

abstract class BasicClean extends Script {
    //abstract def runScript()
    CliBuilder cli
    OptionAccessor opt

    def runScript(URI scriptSourceUri) {
        File scriptSourceFile = Paths.get(scriptSourceUri).toFile()

        cli = new CliBuilder(usage: "groovy ${scriptSourceFile.getName()} --pr=<pull request#> --config=<path>")

        cli.with {
            h(longOpt: 'help', 'Show usage information')
            n(longOpt: 'name', args: 1, argName: 'Name', 'Name', required: false)
            c(longOpt: 'config', args: 1, argName: 'Pipeline config file', 'Pipeline config file', required: true)
            _(longOpt: 'pr', args: 1, argName: 'Pull Request Number', 'GitHub Pull Request #', required: true)
        }


        opt = cli.parse(args)


        if (opt == null) {
            //System.err << 'Error while parsing command-line options.\n'
            //cli.usage()
            System.exit 2
        }

        if (opt?.h) {
            cli.usage()
            return 0
        }


        def config = OpenShiftHelper.loadBuildConfig(opt)
        config.app.namespaces.each {String key, Map value ->
            if (value.disposable == true){
                List newArgs=args + ["--env=${key}"]
                CliBuilder newCli= new CliBuilder()

                BasicDeploy.withOptions(newCli)
                OptionAccessor newOpt = newCli.parse(newArgs)

                def deploymentConfig = OpenShiftHelper.loadDeploymentConfig(newOpt)
                String appName=deploymentConfig.app.name
                String appNamespace=deploymentConfig.app.deployment.namespace
                String appId=deploymentConfig.app.deployment.id
                String appVersion=deploymentConfig.app.deployment.version

                if (key == 'build'){
                    appNamespace=deploymentConfig.app.build.namespace
                    appId=deploymentConfig.app.build.id
                    appVersion=deploymentConfig.app.build.version
                }

                println OpenShiftHelper.oc(['delete','build,bc,dc,rc,svc,routes,secret,pvc,ServiceAccount,RoleBinding', '-l', "app=${appId},!image-stream.name,!shared,!template,!system", '-n', appNamespace]).out.toString().trim()
                Map ret = OpenShiftHelper.ocGet(['is', '-l', "app-name=${appName}", '-n', appNamespace])
                for(Map imageStream in ret.items){
                    OpenShiftHelper.oc(['delete', 'istag', "${imageStream.metadata.name}:${appVersion}", '--ignore-not-found=true', '-n',appNamespace])
                }
            }
        }
    }
}