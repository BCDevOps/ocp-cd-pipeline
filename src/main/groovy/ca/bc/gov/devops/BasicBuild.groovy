package ca.bc.gov.devops

import groovy.cli.picocli.CliBuilder
import groovy.cli.picocli.OptionAccessor
import groovy.transform.BaseScript
import groovy.transform.SourceURI
import java.nio.file.Path
import java.nio.file.Paths

abstract class BasicBuild extends Script {
    //abstract def runScript()
    CliBuilder cli
    OptionAccessor opt

    static public CliBuilder withOptions(CliBuilder _cli){
        _cli.with {
            h(longOpt: 'help', 'Show usage information')
            n(longOpt: 'name', args: 1, argName: 'Name', 'Name', required: false)
            c(longOpt: 'config', args: 1, argName: 'Pipeline config file', 'Pipeline config file', required: true)
            _(longOpt: 'build-name', args: 1, argName: 'Build Name', 'Build Name', required: false)
            _(longOpt: 'pr', args: 1, argName: 'Pull Request Number', 'GitHub Pull Request #', required: true)
        }
        return _cli
    }

    def runScript(URI scriptSourceUri) {
        //println "scriptSourceUri:${scriptSourceUri}"
        //File scriptSourceFile = Paths.get(scriptSourceUri).toFile()

        cli = new CliBuilder(usage: "groovy ${scriptSourceUri} --pr=<pull request#>")

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

        def config = OpenShiftHelper.loadBuildConfig(opt)

        new OpenShiftBuildHelper(config).build()


    }
}