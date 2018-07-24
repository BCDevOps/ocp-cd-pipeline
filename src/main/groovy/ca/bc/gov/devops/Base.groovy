package ca.bc.gov.devops

import static OpenShiftHelper.oc
import static OpenShiftHelper.ocProcess
import static OpenShiftHelper.gitHashAsBlobObject
import static OpenShiftHelper.getVerboseLevel

abstract class Base extends Script {
    static String CMD_OC='oc'
    static String CMD_GIT='git'



    List ocProcessParameters(List args){
        List parameters= []
        StringBuffer stdout= new StringBuffer()
        StringBuffer stderr= new StringBuffer()

        oc(['process'] + args + ['--parameters=true'], stdout, stderr)
        stdout.eachLine {line, number ->
            if (number > 0){
                parameters.add(line.tokenize()[0])
            }
        }
        return parameters
    }

    public static Map _exec(List args){
       return _exec(args.execute())
    }

    public static  Map _exec(java.lang.Process proc){
        return _exec(proc, new StringBuffer(), new StringBuffer())
    }

    public static Map _exec(java.lang.Process proc, StringBuffer stdout, StringBuffer stderr){
        proc.waitForProcessOutput(stdout, stderr)
        int exitValue= proc.exitValue()
        Map ret = ['out': stdout, 'err': stderr, 'status':exitValue]
        return ret
    }

    String key(Map object){
        return "${object.kind}/${object.metadata.name}"
    }

} //end class