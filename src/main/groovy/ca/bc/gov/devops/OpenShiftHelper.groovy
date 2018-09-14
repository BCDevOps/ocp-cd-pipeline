package ca.bc.gov.devops

import groovy.cli.picocli.CliBuilder
import groovy.cli.picocli.OptionAccessor

class OpenShiftHelper{
    protected Map cache = [:] //Indexed by key
    boolean hasError=false

    public static int getVerboseLevel(){
        return 1
    }


    /**
    * Does some thing in old style.
    *
    * @deprecated use {@link #luid()} instead.  
    */
    @Deprecated
    public static String key(Map object){
        return luid(object)
    }

    //Globally Unique Identifier: It is unique accross all projects (mostly used as cache key)
    public static String guid(Map object){
        return "${object.namespace?:object.metadata.namespace}/${object.kind}/${object.name?:object.metadata.name}"
    }

    //Locally Unique Identifier: It is unique within the project it belongs to
    public static String luid(Map object){
        return "${object.kind}/${object.name?:object.metadata.name}"
    }

    public void error(String message){
        hasError=true
        println message
    }
    public void fatal(String message){
        println message
        System.exit(1)
    }

    public void applyCommonTemplateConfig(Map config, Map envConfig, Map object){
        object.metadata.labels['app-name'] = config.app.name
        if (!'true'.equalsIgnoreCase(object.metadata.labels['shared'])){
            if (config.app.git.uri.toLowerCase().startsWith('https://github.com/')) {
                object.metadata.labels['github-owner'] = config.app.git.uri.tokenize('/')[2]
                object.metadata.labels['github-repo'] = config.app.git.uri.tokenize('/')[3].tokenize('.git')[0]
            }

            if (config.app?.git?.changeId){
                object.metadata.labels['change-id'] = config.app.git.changeId
            }
            object.metadata.labels['env-name'] = envConfig.env.name
            object.metadata.labels['env-id'] = envConfig.env.id
            object.metadata.labels['app'] =  envConfig.id
        }
    }

    public List loadTemplates(Map globalConfig, Map templateConfig, Map parameters){
        List templates = templateConfig.templates
        String gitRemoteUri=globalConfig.app.git.uri
        println 'Reading Templates ...'
        templates.each { Map template ->
            if (getVerboseLevel() >= 2) println template.file

            //Load Template
            Map templateObject = new groovy.json.JsonSlurper().parseFile(new File(template.file), 'UTF-8')
            //Normalize template and calculate hash
            templateObject.objects.each { Map it ->
                it.metadata.labels=it.metadata.labels?:[:]
                it.metadata.annotations=it.metadata.annotations?:[:]
                it.metadata.namespace = it.metadata.namespace?:templateConfig.namespace
                if (it.metadata.namespace?.trim()){
                    it.metadata.namespace = templateConfig.namespace
                }
                //Everybody gets a config hash! hooray!
                it.metadata.labels['hash']= gitHashAsBlobObject(groovy.json.JsonOutput.toJson(it))
            }

            List params=['-n', templateConfig.namespace]

            templateObject.parameters.each { param ->
                String name = param.name
                String value = null
                if(template?.params != null){
                    value = template?.params[name]
                }
                value = parameters[name]?:value

                if(value != null){
                    params.addAll(['-p', "${name}=${value}"])
                }
            }

            Map ocRet=ocProcess(templateObject, params)

            Map objects=new groovy.json.JsonSlurper().parseText(ocRet.out.toString())

            objects.items.each {
                println "Loading ${key(it)}"

                it.metadata.labels=it.metadata.labels?:[:]
                it.metadata.annotations=it.metadata.annotations?:[:]
                //normalize to explicit namespace references (it makes things easier)
                it.metadata.namespace = it.metadata.namespace?:templateConfig.namespace

                if ('BuildConfig'.equalsIgnoreCase(it.kind)){
                    if (!it.spec.completionDeadlineSeconds) println "WARN: Please set ${key(it)}.spec.completionDeadlineSeconds"
                    if (!it.spec.completionDeadlineSeconds) println "WARN: Please set ${key(it)}.spec.completionDeadlineSeconds"

                    if (getVerboseLevel() >= 4) println "${it.kind}/${it.metadata.name} - ${it.spec.source.contextDir}"
                    //it.metadata.labels['hash']= gitHashAsBlobObject(groovy.json.JsonOutput.toJson(it))

                    it.metadata.labels['build-config.name'] = it.metadata.name
                    //normalize to explicit namespace references (it makes things easier)
                    it.spec.output.to.namespace = it.spec.output.to.namespace?:templateConfig.namespace

                    if (it.spec?.source?.images != null){
                        for (Map image:it.spec.source?.images){
                            image.from.namespace=image.from.namespace?:templateConfig.namespace
                        }
                    }


                    if (gitRemoteUri.equalsIgnoreCase(it.spec.source?.git?.uri) && it.spec?.source?.contextDir != null){
                        String getTreeHash=_exec(['git', 'ls-tree', 'HEAD', '--', "${it.spec?.source?.contextDir}"]).out.toString().trim().tokenize()[2]
                        it.metadata.labels['tree-hash'] = getTreeHash
                    }else if ('Binary'.equalsIgnoreCase(it.spec.source?.type)){
                        def files=[:]
                        new File("${it.spec?.source?.contextDir}").traverse(type: groovy.io.FileType.FILES){ file ->
                            files[file.getPath()]=[:]
                        }
                        //sort map by key
                        files = files.sort()

                        files.each { String filePath, Map item ->
                            String fileHash=_exec(['git', 'hash-object', '-t', 'blob', '--no-filters', "${filePath}"]).out.toString().trim()
                            item['hash'] = fileHash
                        }

                        String hashSource=groovy.json.JsonOutput.toJson(files)
                        println "source:${hashSource}"

                        //create tar file
                        //find -L module1 -type f -exec sh -c "echo '{}'; git hash-object -t blob --no-filters {}" \;
                        //find -L module1 -type f -print0 | sort -z | xargs -0 git hash-object -t blob --no-filters
                        //-h is required for preserving the contents of symlinks - http://www.gnu.org/software/tar/manual/html_node/dereference.html
                        //_exec(['tar','-chf',"_tmp_${it.metadata.name}.tar", "${it.spec?.source?.contextDir}"])

                        //calculate the hash (using git) of the .tar file
                        //String getTreeHash=_exec(['git', 'hash-object', '-t', 'blob', '--no-filters', "_tmp_${it.metadata.name}.tar"]).out.toString().trim()
                        String checksum=calculateChecksum(hashSource, 'SHA-256')
                        println "checksum:${checksum}"
                        it.metadata.labels['tree-hash'] = checksum
                    }
                    if (it.spec.triggers && it.spec.triggers.size()>0){
                        println "WARN: ${key(it)}.spec.triggers are being removed and will be managed by this build script"
                    }
                    it.spec.triggers = [] //it.spec.triggers.findAll({!'ConfigChange'.equalsIgnoreCase(it.type)})
                    Map strategyOptions=it.spec.strategy.sourceStrategy?:it.spec.strategy.dockerStrategy

                    if (strategyOptions!=null){
                        strategyOptions.env=strategyOptions.env?:[]
                        strategyOptions.env.add(['name':"OPENSHIFT_BUILD_CONFIG_HASH", 'value':"${it.metadata.labels['hash']}"])
                        if (it.metadata.labels['tree-hash']){
                            strategyOptions.env.add(['name':"OPENSHIFT_BUILD_TREE_HASH", 'value':"${it.metadata.labels['tree-hash']}"])
                        }
                        if ('DockerImage' != strategyOptions.from.kind) {
                            strategyOptions.from.namespace = strategyOptions.from.namespace ?: templateConfig.namespace
                        }
                    }
                    it.metadata.labels.remove('tree-hash')
                    //println groovy.json.JsonOutput.toJson(it.spec.triggers)
                }else if ('ImageStream'.equalsIgnoreCase(it.kind)){
                    if (getVerboseLevel() >= 4) println "${it.kind}/${it.metadata.name}"
                    it.metadata.labels['image-stream.name'] = it.metadata.name
                }else if ('CronJob'.equalsIgnoreCase(it.kind)){
                    if (getVerboseLevel() >= 4) println "${it.kind}/${it.metadata.name}"
                    if (!it.spec.jobTemplate.spec.activeDeadlineSeconds) println "WARN: Please set ${key(it)}.spec.jobTemplate.spec.activeDeadlineSeconds"
                    if (!it.spec.jobTemplate.spec.template.spec.activeDeadlineSeconds) println "WARN: Please set ${key(it)}.spec.jobTemplate.spec.template.spec.activeDeadlineSeconds"
                }else if ('DeploymentConfig'.equalsIgnoreCase(it.kind)){
                    //BestPractice
                    it.spec.template.spec.containers.each { container ->
                        if (container.resources.limits.cpu && !container.resources.requests.cpu) println "WARN: Please set ${key(it)}.spec.containers[].resources.requests.cpu"
                    }
                }else{
                    //openshift.io/build-config.name
                    if (getVerboseLevel() >= 4) println "${it.kind}/${it.metadata.name}"
                }
            }
            //println objects
            template.objects=objects.items
        }
        return templates
    } //end function
    public static Map _exec(List args){
        return _exec(args, new StringBuffer(), new StringBuffer())
    }
    static public boolean waitForPodsToComplete(List ocGetAgs){
        int inprogress=1
        boolean hasFailed=false;

        while(inprogress>0){
            Map pods = ocGet(ocGetAgs)
            inprogress=0
            for (Map pod:pods.items){
                if ('Failed' == pod.status.phase || 'Cancelled' == pod.status.phase) {
                    hasFailed = true
                    continue
                }
                if ('Succeeded' == pod.status.phase) continue
                println "Waiting for '${pod.metadata.name}' (${pod.status.phase})"
                OpenShiftHelper._exec(["bash", '-c', "oc logs -f pod/'${pod.metadata.name}' '--namespace=${pod.metadata.namespace}' > /dev/null"], new StringBuffer(), new StringBuffer())
                //OpenShiftHelper._exec(["bash", '-c', "oc attach '${pod.metadata.name}' '--namespace=${pod.metadata.namespace}' > /dev/null"], new StringBuffer(), new StringBuffer())
                inprogress++
            }
            Thread.sleep(2000)
        }
        return !hasFailed
    }
    public static Map _exec(List args, Appendable stdout, Appendable stderr, Closure stdin=null){
        java.time.Instant startInstant = java.time.Instant.now()
        def proc = args.execute()

        if (stdin!=null) {
            OutputStream out = proc.getOutputStream();
            stdin(out)
            out.flush();
            out.close();
        }

        proc.waitForProcessOutput(stdout, stderr)
        int exitValue= proc.exitValue()
        java.time.Duration duration = java.time.Duration.between(startInstant, java.time.Instant.now())

        //if (getVerboseLevel() >= 4)
            println args.join(" ") + " (${duration.getSeconds()}s)"

        Map ret = ['out': stdout, 'err': stderr, 'status':exitValue, 'cmd':args, 'duration':duration]

        return ret
    }

    public static Map oc(List args){
        return oc(args, new StringBuffer(), new StringBuffer())
    }
    
    public static Map oc(List args, Appendable stdout){
        return oc(args, stdout, stdout)
    }

    public static Map oc(List args, Appendable stdout, Appendable stderr){
        List _args = ['oc'];
        _args.addAll(args)

        //def proc = _args.execute()

        Map ret = _exec(_args, stdout, stderr)

        //proc.waitForProcessOutput(stdout, stderr)


        int exitValue= ret.status

        
        //Map ret = ['out': stdout, 'err': stderr, 'status':exitValue, 'cmd':_args]
        if (exitValue != 0){
            throw new RuntimeException("oc returned an error code: ${ret}")
        }
        return ret
    }

    public static Map ocGet(List args){
        List _args = ['get'] + args + ['-o', 'json']
        Map ret=oc(_args)
        if (ret.out!=null && ret.out.length() > 0){
            return toJson(ret.out)
        }
        return null
    }

    public static Map ocProcess(Map template, List args){
        List _args = ['oc', 'process', '-f', '-', '-o', 'json'] + args 
        String json=new groovy.json.JsonBuilder(template).toPrettyString()

        Map ret = _exec(_args, new StringBuffer(), new StringBuffer(), {OutputStream out -> out.write(json.getBytes());})


        if (ret.status !=0){
            throw new RuntimeException("oc returned an error code: ${ret}")
        }
        return ret
    }

    public static Map ocApply(List items, List args){
        List _args = ['oc', 'apply', '-f', '-'] + args 
        String json=new groovy.json.JsonBuilder(['kind':'List', 'apiVersion':'v1', 'items':items]).toPrettyString()


        //println new groovy.json.JsonBuilder(items).toPrettyString()
        Map ret = _exec(_args, new StringBuffer(), new StringBuffer(), {OutputStream out -> out.write(json.getBytes());})


        if (ret.status !=0){
            throw new RuntimeException("oc returned an error code: ${ret}")
        }

        return ret
    }

    public static def toJson(StringBuffer json){
        return toJson(json.toString())
    }
    public static def toJson(String jsonAsText){
        return new groovy.json.JsonSlurper().parseText(jsonAsText)
    }

    /*
    same output as:
       echo 'test content' | git hash-object --stdin --no-filters
       printf "test content\n" | git hash-object --stdin --no-filters
    */
    public static String gitHashAsBlobObject(String content) {
        calculateChecksum("blob ${content.length() + 1 }\0${content}\n", 'SHA1')
    }

    public static String calculateChecksum(String content, String type) {
        def digest = java.security.MessageDigest.getInstance(type)
        def buffer = content.getBytes(java.nio.charset.StandardCharsets.UTF_8)

        digest.update(buffer, 0, buffer.length)

        return digest.digest().encodeHex();
    }

    public static void addBuildConfigEnv(Map buildConfig, Map env){
        Map strategyConfig=buildConfig.spec.strategy.sourceStrategy?:buildConfig.spec.strategy.dockerStrategy
        strategyConfig.env=strategyConfig.env?:[]

        strategyConfig.env.add(env)
    }
    public static Map loadBuildConfig(OptionAccessor opt){
        def configSlurper = new ConfigSlurper("build")
        configSlurper.setBinding(['opt': opt])

        def config = configSlurper.parse(new File(opt.c).toURI().toURL())

        return config
    }
    public static Map loadDeploymentConfig(OptionAccessor opt){
        //println "Loading configuration file for '${opt.e}' ${opt.'pr'}"
        def configFile = new File(opt.c)

        def varsConfigSlurper = new ConfigSlurper(opt.e)
        varsConfigSlurper.setBinding(['opt': opt])

        def varsConfig = varsConfigSlurper.parse(new File(opt.c).toURI().toURL())

        def configSlurper = new ConfigSlurper(opt.e)
        configSlurper.setBinding(['opt': opt, 'vars': varsConfig.vars])

        def config = configSlurper.parse(new File(opt.c).toURI().toURL())
        //config.opt = opt

        return config
    }
    public static OptionAccessor parseArguments(CliBuilder cli, def args){
        OptionAccessor basicOpt

        CliBuilder basicCli = new CliBuilder()

        basicCli.with {
            c(longOpt: 'config', args: 1, argName: 'Pipeline config file', 'Pipeline config file', required: true)
        }

        basicOpt = basicCli.parse(args)


        OptionAccessor opt = cli.parse(args)

        return opt
    }
}