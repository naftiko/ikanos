// groovy-classloader.groovy — tries to use ClassLoader to load arbitrary classes
def cl = Thread.currentThread().getContextClassLoader()
result = cl.toString()
