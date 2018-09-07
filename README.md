# ocp-cd-pipeline

This is a groovy library (provided as jar file) for helping orchestrating the creation of OpenShift objects required for building and deploying applications.

# Motivation

The OpenShift Web Console is a very powerful UI, however it leads to undocumented and manual (click, click) configurations.

The [oc](https://github.com/openshift/origin/blob/master/docs/cli.md) command-line interface is a great alternative to the UI that allows scripted manipulation of OpenShift Objects.

Developers are encouraged to create [templates](https://docs.okd.io/latest/dev_guide/templates.html) and use the template to create/update objects by using the oc command-line. However, we have observed that those templates are not kept up-to-date, and it is not consistently and continuously tested.

In the spirit of [Infrastructue as Code](https://en.wikipedia.org/wiki/Infrastructure_as_Code), and [Continuous Delivery](https://en.wikipedia.org/wiki/Continuous_delivery) Practices, we wanted to (1) test the template as often and as thoroughly as possible,  (2) be able to spin up a new environment as quick and easy as possible, and (3) doing so consistently as part of a [deployment pipeline](https://en.wikipedia.org/wiki/Continuous_delivery#Deployment_pipeline).


## Bye Jenkins Shared Library

Out first attempt to create this library was through using Jenkins [Shared Libraries](https://jenkins.io/doc/book/pipeline/shared-libraries/). However, we have encountered a few challenges:

* Difficult to test: Testing the library and the pipile was very difficult and tedious with lots of trials and errors.
* Strong Dependency to Jenkins: Jenkins was now a strong requirement, and the pipeline can ONLY be executed via Jenkins
* Difficult to develop/troubleshoot: Troubleshooting was done byc hanging live code and introducing a bunch of `println` statements
* Dependency to Jenkins Plugins: The shared library required Openshift Jenkins Client which even though it was a think wrapper of the `oc` command-line, it was somewhat hard to use for managing multiple projects, and difficult to troubleshoot.  

## Hello ocp-cd-pieline library

The first question one might have is _"Why Groovy/Java?"_, for a very simple reason that we started as a Jenkins Shared Library and we started by re-using a big chunk of code from that.

# How does it works?
This Library/utility is designed to rely on OpenShift templates as input and orchestrate the processing and manipulation (Create, Update) of objects as well as applying some lessons learned, best practices, and deal with some quirks related to how templates are processed by oc command-line or OpenShift.

# Features

* Support to [GitHub Flow](https://guides.github.com/introduction/flow/) pipeline: Whenever a PR is created  a complete new and fully-isolated environment is provisioned just for that PR.
* Reuse previously built Images (Fast Builds): It uses a combined hashing mechanism for identifying and reusing images. The built image hash is a combination of `BuildConfig template Hash` + `Build Config Source Images hash` + `Source Code Hash (git tree)`. Whenever one of them changes a new hash is produced.
* Keep Secrets safe: References to secrets still MUST be in the template, but the actual values are copied from another existing Secret
* Support for Binary build Strategy: This is particularly useful for creating base images where the files (e.g.: requirements.txt, packages.json) are symlink to somewhere in the folder structure. The `Source Code Hash` will be the aggregated hash of the content of all included files. Note: Symbolic links are dereferenced using [tar dereference](http://ftp.gnu.org/pub/old-gnu/Manuals/tar-1.12/html_node/tar_115.html) switch (`-h`). 


# Runtime Requirements
* `oc`
* `git`
* `tar`
* `bash`
* `groovy`
* `java`

# How to use it
Example/Starter Groovy scripts are provided in [src/main/groovy/example](./src/main/groovy/example) folder

Create a config file using Groovy [ConfigSlurper](http://docs.groovy-lang.org/latest/html/gapi/groovy/util/ConfigSlurper.html) syntax.

**_Important:_** Make sure the current directory is the git top level directory of the config file

## Build
```
groovy -cp src/main/groovy/ src/main/groovy/example/build.groovy --config=
```

# Build
## Prerequisites
- Java 8: http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html
- Recommended IDE:IntelliJ IDEA

```
# create setting.xml
mvn -c settings.xml deploy
```
