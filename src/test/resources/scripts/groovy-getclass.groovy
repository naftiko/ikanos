// groovy-getclass.groovy — tries to access Class via getClass() on an object
def clazz = "hello".getClass()
def methods = clazz.getMethods()
result = methods.length
