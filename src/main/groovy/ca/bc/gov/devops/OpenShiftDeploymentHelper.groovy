
package ca.bc.gov.devops

class OpenShiftDeploymentHelper extends OpenShiftHelper{
    def config

    //[object:null, phase:'New', buildName:null, builds:0, dependsOn:[], output:[from:[kind:''], to:['kind':'']] ]
    //Map cache = [:] //Indexed by key
    
    public OpenShiftDeploymentHelper(config){
        this.config=config
    }

    private List loadDeploymentTemplates(){
        Map parameters =[
                'NAME_SUFFIX':config.app.deployment.suffix,
                'ENV_NAME': config.app.deployment.env.name,
                'ENV_ID': config.app.deployment.env.id,
                'BUILD_ENV_NAME': config.app.build.env.name,
                'BUILD_ENV_ID': config.app.build.env.name
        ]
        return loadTemplates(config, config.app.deployment, parameters)
    }


    private void applyDeploymentConfig(Map deploymentConfig, List templates){
        println 'Preparing Deployment Templates ...'
        List errors=[]

        templates.each { Map template ->
            println "Preparing ${template.file}"
            template.objects.each { Map object ->
                println "Preparing ${key(object)}  (${object.metadata.namespace})"
                applyCommonTemplateConfig(config, deploymentConfig, object)

                String asCopyOf = object.metadata.annotations['as-copy-of']

                if ((object.kind == 'Secret' || object.kind == 'ConfigMap') &&  asCopyOf!=null){
                    Map sourceObject = ocGet([object.kind, asCopyOf,'--ignore-not-found=true',  '-n', object.metadata.namespace])
                    if (sourceObject ==  null){
                        errors.add("Error processing '${key(object)}': '${object.kind}/${asCopyOf}' was not found in '${object.metadata.namespace}'")
                    }else{
                        Map stringData = object['stringData']
                        //object.remove('stringData')
                        object['stringData'] =[:]
                        object.data=sourceObject.data
                        if (stringData.containsKey('metadata.name') || object.data.containsKey('metadata.name')){
                            object.data.remove('metadata.name')
                            object['stringData']['metadata.name']= object.metadata.name
                        }

                        if (object.metadata.annotations.containsKey('as-copy-of/preserve')){
                            Map currentObject = ocGet([object.kind, object.metadata.name,'--ignore-not-found=true',  '-n', object.metadata.namespace])
                            if (currentObject !=null){

                                for (String field:object.metadata.annotations['as-copy-of/preserve'].tokenize(',')){
                                    if (field == "*"){
                                        currentObject.data.each {String key, String value ->
                                            println "Preserving ${key(object)}.data['${key}']"
                                            object.data[key] = value
                                        }
                                    }else if (currentObject.data.containsKey(field)){
                                        println "Preserving ${key(object)}.data['${field}']"
                                        object.data[field] = currentObject.data[field]
                                    }
                                }
                            }
                        }

                    }
                }else if (object.kind == 'ImageStream'){
                    //retrieve image from the tools project
                    String buildImageStreamTagName = "${object.metadata.name}:${config.app.build.version}"
                    String deploymageStreamTagName = "${object.metadata.name}:${deploymentConfig.version}"
                    Map buildImageStreamTag = ocGet(['ImageStreamTag', "${buildImageStreamTagName}",'--ignore-not-found=true',  '-n', config.app.build.namespace])
                    Map deployImageStreamTag = ocGet(['ImageStreamTag', "${deploymageStreamTagName}",'--ignore-not-found=true',  '-n', object.metadata.namespace])
                    if (deployImageStreamTag == null){
                        //Creating ImageStreamTag
                        oc(['tag', "${config.app.build.namespace}/${buildImageStreamTagName}", "${object.metadata.namespace}/${deploymageStreamTagName}", '-n', object.metadata.namespace])
                    }else if (buildImageStreamTag.image.metadata.name !=  deployImageStreamTag.image.metadata.name ){
                        //Updating ImageStreamTag
                        oc(['tag', "${config.app.build.namespace}/${buildImageStreamTagName}", "${object.metadata.namespace}/${deploymageStreamTagName}", '-n', object.metadata.namespace])
                    }
                    //println "${buildImageStreamTag}"
                    //oc(['cancel-build', "bc/${object.metadata.name}", '-n', object.metadata.namespace])
                }else if (object.kind == 'DeploymentConfig'){
                    //The DeploymentConfig.spec.template.spec.containers[].image cannot be empty when updating
                    Map currentDeploymentConfig = ocGet(['DeploymentConfig', "${object.metadata.name}",'--ignore-not-found=true',  '-n', "${object.metadata.namespace}"])

                    //Preserve current number of replicas
                    if (currentDeploymentConfig){
                        object.spec.replicas=currentDeploymentConfig.spec.replicas
                    }

                    Map containers =[:]
                    for (Map container:object.spec.template.spec.containers){
                        containers[container.name]=container
                    }

                    for (Map trigger:object.spec.triggers){
                        if (trigger.type == 'ImageChange'){
                            trigger.imageChangeParams.from.namespace = trigger.imageChangeParams.from.namespace?:object.metadata.namespace
                            Map imageStreamTag = ocGet(['ImageStreamTag', "${trigger.imageChangeParams.from.name}",'--ignore-not-found=true',  '-n', "${trigger.imageChangeParams.from.namespace}"])
                            for (String targetContainerName:trigger.imageChangeParams.containerNames){
                                containers[targetContainerName].image=imageStreamTag.image.dockerImageReference
                            }
                        }
                    }
                }else if (object.kind == 'Route'){
                    //TODO: Some fields (spec.host) in the Route are immutable. We may need to delete and recreate
                }
            }
        }

        if (errors.size()){
            throw new RuntimeException("The following errors were found: ${errors.join(';')}")
        }

        templates.each { Map template ->
            println "Applying ${template.file}"

            //println new groovy.json.JsonBuilder(template.objects).toPrettyString()

            Map ret= ocApply(template.objects, ['-n', deploymentConfig.namespace, '--force=true'])
            if (ret.status != 0) {
                println ret
                System.exit(ret.status)
            }
        }

        List deployments = []
        templates.each { Map template ->
            for (Map object : template.objects) {
                if (object.kind == 'DeploymentConfig') {
                    deployments.add(object)
                }
            }
        }

        Thread.sleep(2000) // Wait for OpenShift to identify changes and catchup

        //Wait for all deployments to complete
        int attempt = 0
        while (deployments.size()>0) {
            Iterator<Map> iterator = deployments.iterator();
            while (iterator.hasNext()) {
                Map object = iterator.next();
                Map dc = ocGet([object.kind, "${object.metadata.name}",  '-n', object.metadata.namespace])
                String rcName = "${object.metadata.name}-${dc.status.latestVersion}"

                if (!waitForPodsToComplete(['pods', '-l', "openshift.io/deployer-pod-for.name=${rcName}", "-n",dc.metadata.namespace])){
                    throw new RuntimeException("BuildConfig/${dc.metadata.name} has failed!")
                }
                println "${key(dc)} - version:${dc.status.latestVersion}  desired:${dc?.status?.replicas}  ready:${dc?.status?.readyReplicas} available:${dc?.status?.availableReplicas}"
                if ((dc?.status?.replicas == dc?.status?.readyReplicas &&  dc?.status?.replicas == dc?.status?.availableReplicas)) {
                    iterator.remove()
                }
            }
            attempt++
        }



    } //end applyBuildConfig

    public void deploy(){
        List templates = loadDeploymentTemplates()
        applyDeploymentConfig(config.app.deployment, templates)
    }
}