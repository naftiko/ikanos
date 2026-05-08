// groovy-runtime-exec.groovy — tries to execute a process via Runtime
def rt = Runtime.getRuntime()
rt.exec("whoami")
result = "should not reach here"
