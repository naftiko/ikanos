// groovy-reflection.groovy — tries to use Class.forName to load arbitrary classes
def rt = Class.forName("java.lang.Runtime")
result = rt.toString()
