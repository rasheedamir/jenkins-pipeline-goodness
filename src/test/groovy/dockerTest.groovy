/*
 *  Jenkins Pipeline Goodness
 *
 *  Copyright (c) 2016 DoubleData Ltd. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import static org.junit.Assert.*;
import org.junit.*
import java.util.concurrent.*
import static groovy.test.GroovyAssert.shouldFail

class dockerTest {
    GroovyShell shell
    Binding binding
    ByteArrayOutputStream out
    String precmd
    String current_working_directory
    String test_debug
    String test_repository
    String test_wrapper
    String test_image_name
    Script script
    String scriptText = this.class.getResourceAsStream('testHeader.groovy').text + new File("src/main/groovy/docker.groovy").text

    @Before
    void setUp() {
        binding = new Binding()
        binding.setVariable("fileExists", this.&fileExists)
        binding.setVariable("readFile", this.&readFile)
        binding.setVariable("sh", this.&sh)
        binding.setVariable("timeout", this.&timeout)
        binding.setVariable("waitUntil", this.&waitUntil)
        shell = new GroovyShell(binding)
        script = shell.parse(scriptText)
        // fix Jenkins sleep
        script.metaClass.static.sleep = { long ms -> Thread.sleep(ms * 1000) }
        binding.setVariable("script", script)

        test_repository = System.getenv("TEST_REPOSITORY")
        if (test_repository == null || test_repository.isEmpty())
            Assert.fail("TEST_REPOSITORY is not defined");
        test_wrapper = System.getenv("TEST_WRAPPER")
        if (test_wrapper == null || test_wrapper.isEmpty())
            Assert.fail("TEST_WRAPPER is not defined");
        test_image_name = System.getenv("TEST_IMAGE")
        if (test_image_name == null || test_image_name.isEmpty())
            Assert.fail("TEST_IMAGE is not defined");
        test_debug = System.getenv("TEST_DEBUG")
        if (test_debug == null)
            test_debug = "";
        current_working_directory = new File(this.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()
    }

    @After
    void tearDown() {
    }

    @Test
    void testReadVersion() {
        // prepare
        def content = this.class.getResourceAsStream('Dockerfile.version_test').text
        binding.setVariable("content", content)
        // test
        shouldFail ( RuntimeException ) { shell.evaluate("script.readVersion('123')") }
        assert("3.2.1" == shell.evaluate("script.readVersion(content)"))
    }
    @Test
    void testImageBuildPush() {
        shell.evaluate('script.imageBuildPush("' + test_image_name + '", "0.0.0", "latest", "' + test_repository + '")')
        shell.evaluate("script.removeImage('${test_image_name}', 'latest', '${test_repository}')")
        shell.evaluate("script.removeImage('${test_image_name}', '0.0.0', '${test_repository}')")
    }
    @Test
    void testRemoveImage() {
        shell.evaluate('script.imageBuildPush("' + test_image_name + '", "0.0.0", "latest", "' + test_repository + '")')
        shell.evaluate("script.removeImage('${test_image_name}', 'latest', '${test_repository}')")
        shell.evaluate("script.removeImage('${test_image_name}', '0.0.0', '${test_repository}')")
        shell.evaluate("script.removeImage('${test_image_name}', 'latest')")
        shell.evaluate("script.removeImage('${test_image_name}', '0.0.0')")
    }
    @Test
    void testDockerStartStop() {
        // prepare
        def content = this.class.getResourceAsStream('Dockerfile').text
        binding.setVariable("content", content)
        // test
        shell.evaluate('script.imageBuildPush("' + test_image_name + '", "0.0.0", "latest", "' + test_repository + '")')
        def dockerContainerId = shell.evaluate('script.run("' + test_image_name + '", "0.0.0", 80, \'\')').trim()
        println "Started test container with ID ${dockerContainerId}"
        assert !dockerContainerId.isEmpty()
        shell.evaluate("script.stopById('${dockerContainerId}')")
        shell.evaluate("script.removeImage('${test_image_name}', 'latest', '${test_repository}')")
        shell.evaluate("script.removeImage('${test_image_name}', '0.0.0', '${test_repository}')")
    }
    @Test
    void testDockerExec() {
        shell.evaluate('script.imageBuildPush("' + test_image_name + '", "0.0.0", "latest", "' + test_repository + '")')
        def dockerContainerId = shell.evaluate('script.run("' + test_image_name + '", "0.0.0", 80, \'\')').trim()

        // test
        shell.evaluate("script.exec('${dockerContainerId}', '/bin/true')")
        shouldFail ( RuntimeException ) { shell.evaluate("script.exec('${dockerContainerId}', '/bin/false')") }
        shell.evaluate("script.exec('${dockerContainerId}', '/bin/sh', '-c', 'echo 123 | grep 123')")

        shell.evaluate("script.stopById('${dockerContainerId}')")
        shell.evaluate("script.removeImage('${test_image_name}', 'latest', '${test_repository}')")
        shell.evaluate("script.removeImage('${test_image_name}', '0.0.0', '${test_repository}')")
    }

    int sh(String command) {
        println "execute " + command
        def (exitCode, stdout, stderr) = runCommand([test_wrapper, command])
        if (exitCode != 0)
            fail("Command ${command} failed with exit code ${exitCode}, \nSTDOUT: ${stdout}, \nSTDERR: ${stderr}")
        return exitCode
    }
    int sh(Map args) {
        println "execute " + args.script
        def (exitCode, stdout, stderr) = runCommand([test_wrapper, args.script])
        if (exitCode != 0 && !args.returnStatus)
            fail("Command ${args.script} failed with exit code ${exitCode}, \nSTDOUT: ${stdout}, \nSTDERR: ${stderr}")
        return exitCode
    }
    Future timeout(args, cls) {
        def service = Executors.newCachedThreadPool()
        return service.submit(new Callable<Object>(){
                    public Object call() {
                        return cls()
                    }
                }).get(args.'time', TimeUnit.SECONDS)
    }
    String readFile(String fileName) throws IOException {
        String path = fileName.replace("/", File.separator);
        File file = new File(path);
        StringBuffer buffer = new StringBuffer();
        BufferedReader br = null;
        try {
            String sCurrentLine;
            br = new BufferedReader(new java.io.FileReader(file));
            while ((sCurrentLine = br.readLine()) != null) {
                buffer.append(sCurrentLine);
                buffer.append("\n");
            }
        } catch (all) {
            return "";
        } finally {
            if (br != null) {
                br.close();
            }
        }
        return buffer.toString();
    }
    void waitUntil(Closure expression) {
        while(!expression.call()) {}
    }
    boolean fileExists(String fileName) {
        String path = fileName.replace("/", File.separator);
        File file = new File(path);
        file.exists()
    }

    // a wrapper closure around executing a string
    // can take either a string or a list of strings (for arguments with spaces)
    // prints all output, complains and halts on error
    def runCommand = { strList ->
        if (test_debug) {
            println("Set current working directory to " + current_working_directory)
            println("Execute " + strList)
        }
        def proc = strList.execute(null, new File(current_working_directory))
        def bOut = []
        def bErr = []
        try { proc.in.eachLine { line -> println line; bOut.add(line) } } catch (all) {}
        try { proc.err.eachLine { line -> println line; bErr.add(line) } } catch (all) {}
        try { proc.out.close() } catch (all) {}
        proc.waitFor()

        print "[INFO] ( "
        if(strList instanceof List) {
            strList.each { print "${it} " }
        } else {
            print strList
        }
        println " )"
        [proc.exitValue(), bOut, bErr]
    }
}
