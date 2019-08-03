
package ca.bc.gov.devops

class OpenShiftBuildHelper extends OpenShiftHelper{
    def config
    //[object:null, phase:'New', buildName:null, builds:0, dependsOn:[], output:[from:[kind:''], to:['kind':'']] ]
    //Map cache = [:] //Indexed by key
    public OpenShiftBuildHelper(config){
        this.config=config
    }

    private boolean isNextToBuild(Map from, List pending, List processed){
        if (from.namespace != null){
            if (getVerboseLevel() >= 5) println '   (pick) no namespace'
            return true
        }else{
            for( Map bc2:pending){
                Map outputTo=bc2.spec.output.to
                if (getVerboseLevel() >= 5) println "   ${from} - ${key(bc2)}  - output -> ${outputTo}"
                if (outputTo.namespace == null && 'ImageStreamTag'.equalsIgnoreCase(outputTo.kind)){
                    if (outputTo.name.equalsIgnoreCase(from?.name)){
                        if (getVerboseLevel() >= 5) println '   (skip)'
                        return false
                    }
                }
            }
        }
        return true
    }

    Map imageStreamImageLookupCache=[:]
    private Map getImageStreamImage(String namespace, String name){
        String cacheKey="${namespace}/ImageStreamImage/${name}"
        Map imageStreamImage = imageStreamImageLookupCache[cacheKey]

        if (imageStreamImage == null){
            imageStreamImage=ocGet(['ImageStreamImage', name, '-n', namespace])
            imageStreamImageLookupCache[cacheKey]=imageStreamImage
        }
        
        return imageStreamImage
    }
    public List getImageStreamTagByBuildHash(Map ref, String hash){
        if (!'ImageStreamTag'.equalsIgnoreCase(ref?.kind)){
            throw new RuntimeException("Expected kind='ImageStreamTag', but found kind='${ref?.kind}'")
        }
        Map images = [:]
        String namespace=ref.namespace?:config.app.build.namespace
        String imageStreamName=ref.name.split(':')[0]
        Map imageStreamTags=ocGet(['ImageStreamTag','-l',"image-stream.name=${imageStreamName}", '-n', namespace])
        

        for (Map imageStreamTag:imageStreamTags.cache){
            String imageName=imageStreamTag.image.metadata.name
            if (images[imageName]==null){
                Map imageStreamImage = getImageStreamImage(namespace, "${imageStreamName}@${imageName}")
                for (String imageEnv: imageStreamImage.image.dockerImageMetadata.'Config'.'Env'){
                    if ("_BUILD_HASH=${hash}".equalsIgnoreCase(imageEnv)){
                        images[imageName]=imageStreamImage.image.metadata
                        break;
                    }
                }
            }
        }

        return [] + images.values()
        //System.exit(1)
        /// /apis/image.openshift.io/v1/namespaces/csnr-devops-lab-tools/imagestreamimages/

        //oc get is/gwells-pr-719 -o json
        //status.tags[].cache[].image

        //oc export isimage/gwells-pr-719@sha256:b05ed259a8336b69356a586a698dcf9e1d9563c09ef3e5d20a39fdc7bf7a6f86 -o json
        //image.dockerImageMetadata.ContainerConfig.Env[]
        // find 
    }

    public Map getImageStreamTag(Map ref){
        //if (ref == null) return null
        if (!'ImageStreamTag'.equalsIgnoreCase(ref.kind)){
            throw new RuntimeException("Expected kind='ImageStreamTag', but found kind='${ref?.kind}'")
        }
        
        return ocGet(['istag', ref.name, '--ignore-not-found=true', '-n', ref.namespace?:config.app.build.namespace])
    }

    boolean isSameImageStreamTagReference(Map ref1, Map ref2){
        if (
            (ref1.namespace?:config.app.build.namespace).equalsIgnoreCase(ref2.namespace?:config.app.build.namespace) &&
            (ref1.name).equalsIgnoreCase(ref2.name) &&
            (ref1.kind).equalsIgnoreCase(ref2.kind) &&
            'ImageStreamTag'.equalsIgnoreCase(ref1.kind)
        ){
            return true
        }
        return false
    }

    boolean isBuidActive(Map build){
        return 'New' == build.status.phase || 'Pending' == build.status.phase || 'Running' == build.status.phase
    }
    boolean isBuidSuccessful(Map build){
        return 'Complete' == build.status.phase
    }
    boolean allBuildsSuccessful(List builds){
        //for a list of possible status use: oc explain build.status.phase
        boolean allComplete = true
        for (Map build:builds){
            if (!isBuidSuccessful(build)){
                allComplete=false
                println "Waiting for ${key(build)} status.phase = '${build.status.phase}' , expected 'Complete'"
            }
        }
        return allComplete
    }

    public List getImageStreamTags(List references){
        List tags = []
        for (Map imageReference:references){
            Map imageStreamTag=getImageStreamTag(imageReference)
            if (imageStreamTag!=null){
                tags.add(imageStreamTag)
            }
        }
        return tags
    }

    List getBuildConfigFromImageStreamTagReferences(Map bc){
        Map lookup=[:]
        Map from=(bc.spec?.strategy?.dockerStrategy?.from)?:(bc.spec?.strategy?.sourceStrategy?.from)
        from.namespace=from.namespace?:config.app.build.namespace
        lookup["${from.namespace}/${from.kind}/${from.name}"]=from

        if (bc.spec?.source?.images != null){
            for (Map image:bc.spec.source?.images){
                image.from.namespace=image.from.namespace?:config.app.build.namespace
                lookup["${image.from.namespace}/${image.from.kind}/${image.from.name}"]=image.from
            }
        }
        return [] + lookup.values()
    }

    List getLatestRelatedBuilds(bc){
        List builds=[]
        Map dependencies=[:]

        //println "Checking related builds of ${key(bc)}"
        Map from=(bc.spec?.strategy?.dockerStrategy?.from)?:(bc.spec?.strategy?.sourceStrategy?.from)
        dependencies["${from.namespace?:config.app.build.namespace}/${from.kind}/${from.name}"]=from
        if (bc.spec?.source?.images != null){
            for (Map image:bc.spec.source?.images){
                dependencies["${image.from.namespace?:config.app.build.namespace}/${image.from.kind}/${image.from.name}"]=image.from
            }
        }

        Map buildConfigs=ocGet(['bc', '-l', "app=${bc.metadata.labels['app']}", '-n', config.app.build.namespace])
        if (buildConfigs!=null){
            for (Map buildConfig:buildConfigs.cache){
                //println "Checking ${key(buildConfig)}"
                Map imageStreamTagReference=buildConfig.spec.output.to
                if (dependencies["${imageStreamTagReference.namespace?:config.app.build.namespace}/${imageStreamTagReference.kind}/${imageStreamTagReference.name}"]!=null){
                    if (buildConfig.status.lastVersion > 0){
                        Map build=ocGet(["build/${buildConfig.metadata.name}-${buildConfig.status.lastVersion}", '-n', config.app.build.namespace])
                        builds.add(build)
                    }
                }
            }
        }

        //println "${key(bc)} related builds ${builds}"
        return builds
    }


    private Map createBuildHash(Map bc){
        Map record = ['images':[]]
        Map fromImageStreamTag = (bc.spec?.strategy?.dockerStrategy?.from)?:(bc.spec?.strategy?.sourceStrategy?.from)

        Map strategyOptions=bc.spec.strategy.sourceStrategy?:bc.spec.strategy.dockerStrategy
        String sourceHash = null

        strategyOptions.env.each { Map env ->
            if ('OPENSHIFT_BUILD_TREE_HASH' == env.name){
                record['source']=env.value
            }else if ('OPENSHIFT_BUILD_CONFIG_HASH' == env.name){
                record['buildConfig']=env.value
            }
        }

        //record['buildConfig']=bc.metadata.labels['hash']
        //record['source']=bc.metadata.labels['tree-hash']
        record['images']<<getImageStreamTag(fromImageStreamTag)?.image?.metadata?.name

        //Handles chained Builds
        if (bc.spec?.source?.images != null){
            for (Map image:bc.spec.source?.images){
                Map from = image.from
                record['images']<<getImageStreamTag(from)?.image?.metadata?.name
            }
        }
        String hashSource=groovy.json.JsonOutput.toJson(record)
        bc.metadata.annotations['build-hash-source']=hashSource
        String checksum='sha256:'+calculateChecksum(hashSource, 'SHA-256')
        bc.metadata.annotations['build-hash']=checksum

        return ['source':hashSource, 'checksum':checksum]
    }

    private int pickNextItemsToBuild(List processed, List pending, List queue){
        int newItems=0
        List snapshot=[]
        snapshot.addAll(pending)

        def iterator = pending.iterator()

        while (iterator.hasNext()){
            Map bc = iterator.next()
            boolean picked=false

            //println "Checking ${key(bc)} spec.strategy"

            if ('ImageStreamTag'.equalsIgnoreCase(bc.spec?.strategy?.dockerStrategy?.from?.kind)){
                if (isNextToBuild(bc.spec?.strategy?.dockerStrategy?.from, snapshot, processed)){
                    picked = true
                }
            }else if ('ImageStreamTag'.equalsIgnoreCase(bc.spec?.strategy?.sourceStrategy?.from?.kind)){
                if (isNextToBuild(bc.spec?.strategy?.sourceStrategy?.from, snapshot, processed)){
                    picked = true
                }
            }else{
                throw new RuntimeException("I don't know how to handle this type of build! ${key(bc)}  -  :`(")
            }

            //Check for source of Chained Builds
            if (picked && bc.spec?.source?.images != null){
                //println "Checking ${key(bc)} spec.source.images"
                for (Map image:bc.spec.source?.images){
                    if (!isNextToBuild(image.from, snapshot, processed)){
                        picked=false
                        break;
                    }
                }
            }

            if (picked){
                newItems++
                queue.add(bc)
                iterator.remove()
            }
        }

        return newItems
    }

    private void applyBuildConfig(Map config, List templates){
        println 'Applying Build Templates ...'
        templates.each { Map template ->
            template.objects.each { object ->
                applyCommonTemplateConfig(config, config.app.build, object)

                if (object.kind == 'BuildConfig'){
                    if (config.app.git.uri.equalsIgnoreCase(object.spec?.source?.git?.uri)){
                        object.spec.source.git.ref= config.app.git.ref
                    }
                }
            }

            //println new groovy.json.JsonBuilder(template.objects).toPrettyString()
            Map ret= ocApply(template.objects, ['-n', config.app.build.namespace])

            if (ret.status != 0) {
                println ret
                System.exit(ret.status)
            }
        }
    } //end applyBuildConfig

    private List loadBuildTemplates(Map config){
        Map parameters =[
            'NAME_SUFFIX':config.app.build.suffix,
            'ENV_NAME': config.app.build.env.name,
            'ENV_ID': config.app.build.env.id,
            'SOURCE_REPOSITORY_URL': config.app.git.uri,
            'SOURCE_REPOSITORY_REF': config.app.git.ref
        ]

        return loadTemplates(config, config.app.build, parameters)
    }

    public void build(){
        List pending=[]
        List processing=[]
        List processed=[]
        Map indexByKey=[:]

        //println 'Building ...'

        List templates = loadBuildTemplates(config)

        applyBuildConfig(config, templates)

        //Loading objects from the template into cache
        templates.each { Map template ->
            template.objects.each { object ->
                Map item = cache["${OpenShiftHelper.guid(object)}"] = ['object':object, 'phase':'New']
            }
        }

        //println cache
        def lookupImageStreamByImageStreamTag = {Map refImageStreamTag ->
            //println "> lookupImageStreamByImageStreamTag () - ${refImageStreamTag}"
            if ('DockerImage' == refImageStreamTag.kind) return null

            String outputImageStreamTagGuid = OpenShiftHelper.guid(refImageStreamTag)
            Map outputImageStreamTagEntry = cache[outputImageStreamTagGuid]
            if (outputImageStreamTagEntry == null ) {
                //println "Creating cache Entry for '${outputImageStreamTagGuid}'"
                outputImageStreamTagEntry = [object:['kind':refImageStreamTag.kind, 'metadata':['namespace':refImageStreamTag.namespace, 'name':refImageStreamTag.name]], 'phase':'cached']
                cache[outputImageStreamTagGuid] = outputImageStreamTagEntry
            }

            Map refOutputImageStream = ['kind':'ImageStream', 'metadata':['namespace':outputImageStreamTagEntry.object.metadata.namespace, 'name':outputImageStreamTagEntry.object.metadata.name.split(':')[0]]]
            String outputImageStreamGuid = OpenShiftHelper.guid(refOutputImageStream)
            Map outputImageStreamEntry = cache[outputImageStreamGuid]
            if (outputImageStreamEntry == null ) {
                //println "Creating cache Entry for '${outputImageStreamGuid}'"
                outputImageStreamEntry = [object:['kind':refOutputImageStream.kind, 'metadata':['namespace':refOutputImageStream.namespace, 'name':refOutputImageStream.name]], 'phase':'cached']
                cache[outputImageStreamGuid] = outputImageStreamEntry
            }
            outputImageStreamTagEntry['imageStream'] = outputImageStreamEntry

            return outputImageStreamEntry
        }

        //Link BuildConfig to ImageStream
        println 'Fetching ImageStreamTags'
        for (Map item:cache.values().toArray()){
            if ('BuildConfig' == item?.object?.kind){
                Map object = item.object
                //println "Preparing ${OpenShiftHelper.guid(object)}"
                //println object.spec.output.to
                Map outputImageStream = lookupImageStreamByImageStreamTag(object.spec.output.to)
                outputImageStream['buildConfig'] = item
                item['imageStream'] = outputImageStream

                lookupImageStreamByImageStreamTag(object.spec?.strategy?.dockerStrategy?.from?:object.spec?.strategy?.sourceStrategy?.from)

                //Now deal with Chained Builds
                for (Map image:object.spec.source?.images){
                    lookupImageStreamByImageStreamTag(image.from)
                }
            }
        }

        
        //Collect BuildConfig Dependencies
        println 'Collecting BuildConfig Interdependencies'
        for (Map item:cache.values().toArray()){
            Map object = item.object
            if ('BuildConfig'.equalsIgnoreCase(object.kind)){
                //println "Dependencies ${OpenShiftHelper.guid(object)}"
                List dependencies = []
                Map entry1=lookupImageStreamByImageStreamTag(object.spec?.strategy?.dockerStrategy?.from?:object.spec?.strategy?.sourceStrategy?.from)
                if (entry1!=null) {
                    if (entry1['buildConfig'] != null) {
                        dependencies.add(entry1['buildConfig'])
                    }
                }

                for (Map image:object.spec?.source?.images){
                    if ('ImageStreamTag' == image.from.kind){
                        Map entry2=lookupImageStreamByImageStreamTag(image.from)
                        if (entry2['buildConfig']!=null){
                            dependencies.add(entry2['buildConfig'])
                        }
                    }
                }
                item['dependencies']=dependencies
            }
        }

        int iteration = 0;
        java.time.Instant startInstant = java.time.Instant.now()
        java.time.Duration duration = java.time.Duration.ZERO

        //First of all, cancel all builds in progress! (or should it wait for them to finish?)
        println 'Cancelling all builds in-progress'
        for (Map item:cache.values().toArray()){
                Map object = item.object
                if ('BuildConfig'.equalsIgnoreCase(object.kind)){
                    oc(['cancel-build', "bc/${object.metadata.name}", '-n', object.metadata.namespace])
                }
        }

        println 'Building ...'
        while (true){
            iteration ++
            int pendingItems = 0
            int sleepInSeconds = -1

            boolean triggered=false
            for (Map item:cache.values().toArray()){
                Map object = item.object
                if ('BuildConfig'.equalsIgnoreCase(object.kind)){
                    if ('Complete' == item.phase) continue
                    if ('Cancelled' == item.phase) continue
                    if ('Failed' == item.phase) continue

                    println "Processing  - ${OpenShiftHelper.guid(object)}  status:${item.phase} attempts:${item.attempts?:0}"
                    pendingItems++
                    
                    boolean dependenciesMet=true
                    for (Map dependency:item.dependencies){
                        println "  > ${OpenShiftHelper.guid(dependency.object)} - status:${dependency.phase}"
                        if ('Complete' != dependency.phase){
                            dependenciesMet = false
                        }
                    } //end for
                    if (!dependenciesMet){
                        item.phase = 'Waiting'
                        //println "  skip (dependencies not met yet)"
                        continue
                    }


                    //println "Processing  - ${OpenShiftHelper.guid(object)}  status:${item.phase} attempts:${item.attempts?:0}  (${iteration})"
                    if ( 'New' == item.phase || 'Waiting' == item.phase ){
                        Map outputTo = object.spec.output.to
                        /*
                        List imageStreamTagReferences = getBuildConfigFromImageStreamTagReferences(object)
                        List imageStreamTags = getImageStreamTags(imageStreamTagReferences)

                        if (imageStreamTagReferences.size() != imageStreamTags.size()){
                            throw new RuntimeException("One or more required images are missing!")
                        }
                        */

                        Map buildChecksum = createBuildHash(object)
                        String buildHash=buildChecksum.checksum


                        Map outputImageStreamEntry = item['imageStream']
                        Map imageStreamTags=ocGet(['ImageStreamTag','-l',"image-stream.name=${outputImageStreamEntry.object.metadata.name}", '-n', object.metadata.namespace])
                        Map images = [:]

                        for (Map imageStreamTag:imageStreamTags.items){
                            String imageName=imageStreamTag.image.metadata.name
                            if (images[imageName]==null){
                                Map imageStreamImage = getImageStreamImage(object.metadata.namespace, "${outputImageStreamEntry.object.metadata.name}@${imageName}")
                                for (String imageEnv: imageStreamImage.image.dockerImageMetadata.'Config'.'Env'){
                                    if (imageEnv.startsWith("_BUILD_HASH")){
                                        if ("_BUILD_HASH=${buildHash}".equalsIgnoreCase(imageEnv)){
                                            images[imageName]=imageStreamImage.image.metadata
                                            break;
                                        }
                                    }
                                }
                            }
                        }

                        //println images
                        //List images=getImageStreamTagByBuildHash(outputTo, buildHash)
                        //System.exit(1)
                        //object.metadata.annotations['build-hash-checksum'] = buildChecksum.checksum
                        //object.metadata.annotations['build-hash-source'] = buildChecksum.source
                        //ocApply([object], ['-n', config.app.build.namespace])

                        if (images.size() == 0){
                            //println "Build Hash (Source):\n${buildChecksum.source}"
                            addBuildConfigEnv(object, [name:'_BUILD_HASH', value:buildHash])
                            object.metadata.annotations['build-hash'] = buildChecksum.checksum
                            object.metadata.annotations['build-hash-source'] = buildChecksum.source
                            Map ocApplyRet=ocApply([object], ['-n', config.app.build.namespace])
                            //Start New Build
                            //TODO: is nuild already in progress?
                            println "Starting New Build for ${key(object)}"
                            //item.phase = 'Complete'
                            item['attempts']=(item['attempts']?:0) + 1
                            item.phase = 'Pending'
                            if ('Binary'.equalsIgnoreCase(object.spec.source?.type)){
                                File tempTarFile= new File("_tmp_${object.metadata.name}.tar")
                                //create a temporary tar file
                                _exec(['tar','-chf',"${tempTarFile.path}", "${object.spec?.source?.contextDir}"])
                                item.'build-name' = oc(['start-build', object.metadata.name, '-n', object.metadata.namespace, '-o', 'name', "--from-archive=${tempTarFile.path}"]).out.toString().trim()
                                //delete temporary tar file
                                tempTarFile.delete()
                            }else {
                                item.'build-name' = oc(['start-build', object.metadata.name, '-n', object.metadata.namespace, '-o', 'name']).out.toString().trim()
                            }
                            //sleepInSeconds=Math.max(sleepInSeconds, 5)
                            triggered=true
                            
                        }else {
                            if (images.size() > 1){
                                println "WARNING: More than 1 image with the same build hash (${buildHash}) found for ${object.metadata.name} - ${images.values().collect({ it.name }).join(',')}"
                            }
                            Map imageStreamImage = images.values()[0]
                            println "Reusing existing image (${imageStreamImage.name}) for ${key(object)}"
                            Map outputImageStreagTag = getImageStreamTag(outputTo)
                            if (outputImageStreagTag==null || !imageStreamImage.name.equalsIgnoreCase(outputImageStreagTag.image.metadata.name)){
                                oc(['tag', '--source=imagestreamimage', "${outputTo.namespace?:config.app.build.namespace}/${outputTo.name.split(':')[0]}@${imageStreamImage.name}", "${outputTo.namespace?:config.app.build.namespace}/${outputTo.name}"])
                            }
                            item.phase = 'Complete'
                        }
                    }else if ( 'Running' == item.phase  || 'Pending' == item.phase){
                        Map build = ocGet(["${item['build-name']}", '-n', object.metadata.namespace])
                        item['build-status'] = build.status
                        
                        //since we are using the same status/phase name, don't let it reset to 'New'
                        if ('New' != build.status.phase ) item.phase = build.status.phase

                    }
                    // println "${OpenShiftHelper.guid(object)}  status:${item.phase} attempts:${item.attempts?:0}  (${iteration})"
                } //end if (BuildConfig)
            } //end for

            //println "pendingItems  - ${pendingItems}"
            duration = java.time.Duration.between(startInstant, java.time.Instant.now())
            if (pendingItems == 0) break //nothing left to process (everything is either 'Complete' or 'Cancelled'
            if (triggered || sleepInSeconds == -1 ){
                sleepInSeconds=4
            }


            if (!triggered){
                for (Map item:cache.values().toArray()) {
                    Map object = item.object
                    if ('BuildConfig'.equalsIgnoreCase(object.kind)) {
                        if ('Complete' == item.phase) continue
                        if ('Cancelled' == item.phase) continue
                        if ('Failed' == item.phase) continue
                        if ('New' == item.phase) continue
                        if ('Waiting' == item.phase) continue

                        if (item['build-name']){
                            String shortBuildName = item['build-name'].tokenize('/')[1]

                            if (!waitForPodsToComplete(['pods', '-l', "openshift.io/build.name=${shortBuildName}", "--namespace=${config.app.build.namespace}"])){
                                throw new RuntimeException("'${shortBuildName}' did NOT complete successfully")
                            }
                            //only wait for 1 build/pod to complete at the time
                            break
                        }

                        //_exec(["bash", '-c', "oc attach '${pod.metadata.name}' '--namespace=${pod.metadata.namespace}' > /dev/null"], new StringBuffer(), new StringBuffer())
                    }
                }
            }
            //println "Sleeping for ${sleepInSeconds} seconds"
            //Thread.sleep(sleepInSeconds * 1000) // 4 seconds
            //println "Elapsed Seconds: ${duration.getSeconds()} (max = ${config.app.build.timeoutInSeconds})"
            if (duration.getSeconds() > config.app.build.timeoutInSeconds) throw new java.util.concurrent.TimeoutException("Expected to take no more than ${config.app.build.timeoutInSeconds} seconds.")
        } // end while(true)

        //Delete temporary tar files
        new File('.').traverse(type: groovy.io.FileType.FILES, nameFilter: ~/_tmp_.*/) { it ->
            println "Deleting ${it.name}"
            it.delete()
        }

        duration = java.time.Duration.between(startInstant, java.time.Instant.now())
        println "Elapsed Seconds: ${duration.getSeconds()} (max = ${config.app.build.timeoutInSeconds})"
    } //end build
}