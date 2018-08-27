app {
    name = 'gwells'
    version = 'snapshot'

    git {
        workDir = ['git', 'rev-parse', '--show-toplevel'].execute().text.trim()
        uri = ['git', 'config', '--get', 'remote.origin.url'].execute().text.trim()
        commit = ['git', 'rev-parse', 'HEAD'].execute().text.trim()
        ref = ['bash','-c', 'git config branch.`git name-rev --name-only HEAD`.merge'].execute().text.trim()
        changeId = '697'
        github {
            owner = app.git.uri.tokenize('/')[2]
            name = app.git.uri.tokenize('/')[3].tokenize('.git')[0]
        }
    }

    build {
        name = "pr-${app.git.changeId}"
        prefix = "${app.name}-"
        suffix = "-${app.git.changeId}"
        namespace = 'csnr-devops-lab-tools'
        timeoutInSeconds = 60*20 // 20 minutes
        templates = [
                ['file':'../openshift/postgresql.bc.json'],
                ['file':'../openshift/backend.bc.json']
        ]
    }

    deployment {
        TARGET_ENV_NAME = 'dev'
        name = "pr-${app.git.changeId}"
        prefix = "${app.name}-"
        suffix = "-${app.git.changeId}"
        namespace = 'csnr-devops-lab-deploy'
        timeoutInSeconds = 60*20 // 20 minutes
        templates = [
                ['file':'../openshift/postgresql.dc.json',
                'params':[
                    'DATABASE_SERVICE_NAME':"gwells-pgsql${app.deployment.suffix}",
                    'IMAGE_STREAM_NAMESPACE':'',
                    'IMAGE_STREAM_NAME':"gwells-postgresql${app.deployment.suffix}",
                    'IMAGE_STREAM_VERSION':"${app.deployment.name}",
                    'POSTGRESQL_DATABASE':'gwells',
                    'VOLUME_CAPACITY':"${app.env[app.deployment.TARGET_ENV_NAME]?.params?.DB_PVC_SIZE?:'1Gi'}"
                    ]
                ],
                ['file':'../openshift/backend.dc.json']
        ]
    }

    env: [
        'dev':[:],
        'test':[
            'params':[
                'host':'gwells-test.pathfinder.gov.bc.ca',
                'DB_PVC_SIZE':'5Gi'
            ]
        ],
        'prod':[
            'params':[
                'host':'gwells-prod.pathfinder.gov.bc.ca',
                'DB_PVC_SIZE':'5Gi'
            ]
        ]
    ]
}