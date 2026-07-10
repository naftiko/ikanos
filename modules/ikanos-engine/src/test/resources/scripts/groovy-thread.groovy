// groovy-thread.groovy — tries to create a new thread
def t = new Thread({ println "escaped" })
t.start()
result = "should not reach here"
