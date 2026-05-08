// groovy-process-builder.groovy — tries to spawn a process via ProcessBuilder
def pb = new ProcessBuilder("whoami")
pb.start()
result = "should not reach here"
