package org.jenkinsci.plugins.workflow.cps.actions;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import hudson.EnvVars;
import hudson.Functions;
import hudson.XmlFile;
import hudson.model.Action;
import org.apache.commons.lang.RandomStringUtils;
import org.jenkinsci.plugins.credentialsbinding.impl.BindingStep;
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.cps.CpsThread;
import org.jenkinsci.plugins.workflow.cps.nodes.DescriptorMatchPredicate;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.FlowScanningUtils;
import org.jenkinsci.plugins.workflow.graphanalysis.ForkScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.LinearScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.NodeStepTypePredicate;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.EchoStep;
import org.jenkinsci.plugins.workflow.support.storage.SimpleXStreamFlowNodeStorage;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Tests the input sanitization and step persistence here
 */
public class ArgumentsActionImplTest {
    // Run pipeline, with pauses for steps
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public JenkinsRule r = new JenkinsRule();

    /** Helper function to test direct file deserialization for an execution */
    private void testDeserialize(FlowExecution execution) throws Exception {
        if (!(execution instanceof CpsFlowExecution) || !(((CpsFlowExecution)execution).getStorage() instanceof SimpleXStreamFlowNodeStorage)) {
            return;  // Test is unfortunately coupled to the implementation -- otherwise it will simply hit caches
        }

        SimpleXStreamFlowNodeStorage storage = (SimpleXStreamFlowNodeStorage)(((CpsFlowExecution)execution).getStorage());
        Method getFileM = SimpleXStreamFlowNodeStorage.class.getDeclaredMethod("getNodeFile", String.class);
        getFileM.setAccessible(true);

        List<FlowNode> nodes = new DepthFirstScanner().allNodes(execution.getCurrentHeads());
        Collections.sort(nodes, FlowScanningUtils.ID_ORDER_COMPARATOR);

        Field nodeExecutionF = FlowNode.class.getDeclaredField("exec");
        nodeExecutionF.setAccessible(true);

        // Read each node via deserialization from storage, and sanity check the node, the actions, and the ArgumentsAction read back right
        for (FlowNode f : nodes) {
            XmlFile file = (XmlFile)(getFileM.invoke(storage, f.getId()));
            Object tagObj = file.read();
            Assert.assertNotNull(tagObj);

            // Check actions & node in the Tag object, but without getting at the private Tag class
            Field actionField = tagObj.getClass().getDeclaredField("actions");
            Field nodeField = tagObj.getClass().getDeclaredField("node");

            actionField.setAccessible(true);
            nodeField.setAccessible(true);

            Action[] deserializedActions = (Action[]) actionField.get(tagObj);
            FlowNode deserializedNode = (FlowNode)(nodeField.get(tagObj));
            nodeExecutionF.set(deserializedNode, f.getExecution());

            Assert.assertNotNull(deserializedNode);
            if (f.getActions().size() > 0) {
                Assert.assertNotNull(deserializedActions);
                Assert.assertEquals(f.getActions().size(), deserializedActions.length);
            }

            ArgumentsAction expectedInfoAction = f.getPersistentAction(ArgumentsAction.class);
            if (expectedInfoAction != null) {
                Action deserializedInfoAction = Iterables.getFirst(Iterables.filter(Lists.newArrayList(deserializedActions), Predicates.instanceOf(ArgumentsAction.class)), null);
                Assert.assertNotNull(deserializedInfoAction);
                ArgumentsAction ArgumentsAction = (ArgumentsAction)deserializedInfoAction;

                // Compare original and deserialized step arguments to see if they match
                Assert.assertEquals(ArgumentsAction.getStepArgumentsAsString(f), ArgumentsAction.getStepArgumentsAsString(deserializedNode));
                Map<String,Object> expectedParams = expectedInfoAction.getArguments();
                Map<String, Object> deserializedParams = ArgumentsAction.getArguments();
                Assert.assertEquals(expectedParams.size(), deserializedParams.size());
                for (String s : expectedParams.keySet()) {
                    Object expectedVal = expectedParams.get(s);
                    Object actualVal = deserializedParams.get(s);
                    if (expectedVal instanceof  Comparable) {
                        Assert.assertEquals(actualVal, expectedVal);
                    }
                }

            }
        }
    }

    @Test
    public void testStringSafetyTest() throws Exception {
        String input = "I have a secret p4ssw0rd";
        HashMap<String,String> passwordBinding = new HashMap<String,String>();
        passwordBinding.put("mypass", "p4ssw0rd");
        Assert.assertTrue("Input with no variables is safe", ArgumentsActionImpl.isStringSafe(input, new EnvVars(), Collections.EMPTY_SET));
        Assert.assertFalse("Input containing bound value is unsafe", ArgumentsActionImpl.isStringSafe(input, new EnvVars(passwordBinding), Collections.EMPTY_SET));

        Assert.assertTrue("EnvVars that do not occur are safe", ArgumentsActionImpl.isStringSafe("I have no passwords", new EnvVars(passwordBinding), Collections.EMPTY_SET));

        HashMap<String, String> safeBinding = new HashMap<String,String>();
        safeBinding.put("harmless", "secret");
        HashSet<String> safeVars = new HashSet<String>();
        safeVars.add("harmless");
        passwordBinding.put("harmless", "secret");
        Assert.assertTrue("Input containing whitelisted bound value is safe", ArgumentsActionImpl.isStringSafe(input, new EnvVars(safeBinding), safeVars));
        Assert.assertFalse("Input containing one safe and one unsafe bound value is unsafe", ArgumentsActionImpl.isStringSafe(input, new EnvVars(passwordBinding), safeVars));
    }

    @Test
    public void testBasicCreateAndMask() throws Exception {
        HashMap<String,String> passwordBinding = new HashMap<String,String>();
        passwordBinding.put("mypass", "p4ssw0rd");
        Map<String, Object> arguments = new HashMap<String,Object>();
        arguments.put("message", "I have a secret p4ssw0rd");

        // Same string, unsanitized
        ArgumentsActionImpl argumentsActionImpl = new ArgumentsActionImpl(arguments, new EnvVars());
        Assert.assertEquals(false, argumentsActionImpl.isModifiedBySanitization());
        Assert.assertEquals(arguments.get("message"), argumentsActionImpl.getArgumentValueOrReason("message"));
        Assert.assertEquals(1, argumentsActionImpl.getArguments().size());
        Assert.assertEquals("I have a secret p4ssw0rd", argumentsActionImpl.getArguments().get("message"));

        // Test sanitizing arguments now
        argumentsActionImpl = new ArgumentsActionImpl(arguments, new EnvVars(passwordBinding));
        Assert.assertEquals(true, argumentsActionImpl.isModifiedBySanitization());
        Assert.assertEquals(ArgumentsActionImpl.NotStoredReason.MASKED_VALUE, argumentsActionImpl.getArgumentValueOrReason("message"));
        Assert.assertEquals(1, argumentsActionImpl.getArguments().size());
        Assert.assertEquals(ArgumentsAction.NotStoredReason.MASKED_VALUE, argumentsActionImpl.getArguments().get("message"));

        // Mask oversized values
        int length = ArgumentsAction.MAX_STRING_LENGTH;
        arguments.clear();
        arguments.put("text", RandomStringUtils.random(length+1));
        argumentsActionImpl = new ArgumentsActionImpl(arguments);
        Assert.assertEquals(ArgumentsAction.NotStoredReason.OVERSIZE_VALUE, argumentsActionImpl.getArgumentValueOrReason("text"));
    }

    @Test
    public void testBasicCredentials() throws Exception {
        String username = "bob";
        String password = "s3cr3t";
        UsernamePasswordCredentialsImpl c = new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "test", "sample", username, password);
        CredentialsProvider.lookupStores(r.jenkins).iterator().next().addCredentials(Domain.global(), c);

        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "credentialed");
        job.setDefinition(new CpsFlowDefinition(
                "node{ withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'test',\n" +
                "                usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {\n" +
                "    //available as an env variable, but will be masked if you try to print it out any which way\n" +
                "    echo \"$PASSWORD'\" \n" +
                "    echo \"${env.USERNAME}\"\n" +
                "    echo \"bob\"\n" +
                "} }\n" +
                "withCredentials([usernamePassword(credentialsId: 'test', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {\n"+
                "  echo \"${env.USERNAME} ${env.PASSWORD}\"\n"+
                "}"
        ));
        WorkflowRun run  = job.scheduleBuild2(0).getStartCondition().get();
        r.waitForCompletion(run);
        FlowExecution exec = run.getExecution();
        String log = r.getLog(run);
        ForkScanner scanner = new ForkScanner();
        List<FlowNode> filtered = scanner.filteredNodes(exec, new DescriptorMatchPredicate(BindingStep.DescriptorImpl.class));

        // Check the binding step is OK
        Assert.assertEquals(8, filtered.size());
        FlowNode node = Collections2.filter(filtered, FlowScanningUtils.hasActionPredicate(ArgumentsActionImpl.class)).iterator().next();
        ArgumentsActionImpl act = node.getPersistentAction(ArgumentsActionImpl.class);
        Assert.assertNotNull(act.getArgumentValue("bindings"));
        Assert.assertNotNull(act.getArguments().get("bindings"));

        // Test that masking really does mask bound credentials appropriately
        filtered = scanner.filteredNodes(exec, new DescriptorMatchPredicate(EchoStep.DescriptorImpl.class));
        for (FlowNode f : filtered) {
            act = f.getPersistentAction(ArgumentsActionImpl.class);
            Assert.assertEquals(ArgumentsAction.NotStoredReason.MASKED_VALUE, act.getArguments().get("message"));
            Assert.assertNull(ArgumentsAction.getStepArgumentsAsString(f));
        }

        List<FlowNode> allStepped = scanner.filteredNodes(run.getExecution().getCurrentHeads(), FlowScanningUtils.hasActionPredicate(ArgumentsActionImpl.class));
        Assert.assertEquals(6, allStepped.size());  // One ArgumentsActionImpl per block or atomic step

        testDeserialize(exec);
    }

    @Test
    public void simpleSemaphoreStep() throws Exception {
        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition(
                "semaphore 'wait'"
        ));
        WorkflowRun run  = job.scheduleBuild2(0).getStartCondition().get();
        SemaphoreStep.waitForStart("wait/1", run);
        FlowNode semaphoreNode = run.getExecution().getCurrentHeads().get(0);
        CpsThread thread = CpsThread.current();
        SemaphoreStep.success("wait/1", null);
        r.waitForCompletion(run);
        testDeserialize(run.getExecution());
    }

    @Test
    public void testArgumentDescriptions() throws Exception {
        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "argumentDescription");
        job.setDefinition(new CpsFlowDefinition(
                "echo 'test' \n " +
                        " node('master') { \n" +
                        "   retry(3) {\n"+
                        "     if (isUnix()) { \n" +
                        "       sh 'whoami' \n" +
                        "     } else { \n"+
                        "       bat 'echo %USERNAME%' \n"+
                        "     }\n"+
                        "   } \n"+
                        "}"
        ));
        WorkflowRun run = r.buildAndAssertSuccess(job);
        LinearScanner scan = new LinearScanner();

        // Argument test
        FlowNode echoNode = scan.findFirstMatch(run.getExecution().getCurrentHeads().get(0), new NodeStepTypePredicate("echo"));
        Assert.assertEquals("test", echoNode.getPersistentAction(ArgumentsAction.class).getArguments().values().iterator().next());
        Assert.assertEquals("test", ArgumentsAction.getStepArgumentsAsString(echoNode));

        if (Functions.isWindows()) {
            FlowNode batchNode = scan.findFirstMatch(run.getExecution().getCurrentHeads().get(0), new NodeStepTypePredicate("bat"));
            Assert.assertEquals("echo %USERNAME%", batchNode.getPersistentAction(ArgumentsAction.class).getArguments().values().iterator().next());
            Assert.assertEquals("echo %USERNAME%", ArgumentsAction.getStepArgumentsAsString(batchNode));
        } else { // Unix
            FlowNode shellNode = scan.findFirstMatch(run.getExecution().getCurrentHeads().get(0), new NodeStepTypePredicate("sh"));
            Assert.assertEquals("whoami", shellNode.getPersistentAction(ArgumentsAction.class).getArguments().values().iterator().next());
            Assert.assertEquals("whoami", ArgumentsAction.getStepArgumentsAsString(shellNode));
        }

        FlowNode nodeNode = scan.findFirstMatch(run.getExecution().getCurrentHeads().get(0),
                Predicates.and(Predicates.instanceOf(StepStartNode.class), new NodeStepTypePredicate("node"), FlowScanningUtils.hasActionPredicate(ArgumentsActionImpl.class)));
        Assert.assertEquals("master", nodeNode.getPersistentAction(ArgumentsAction.class).getArguments().values().iterator().next());
        Assert.assertEquals("master", ArgumentsAction.getStepArgumentsAsString(nodeNode));

        testDeserialize(run.getExecution());
    }

    @Test
    public void testUnusualStepInstantiations() throws Exception {
        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "unusualInstantiation");
        job.setDefinition(new CpsFlowDefinition(
                        " node('master') { \n" +
                        "   writeFile text: 'hello world', file: 'msg.out'\n" +
                        "   step([$class: 'ArtifactArchiver', artifacts: 'msg.out', fingerprint: false])\n "+
                        "   withEnv(['CUSTOM=val']) {\n"+  //Symbol-based, because withEnv is a metastep
                        "     echo env.CUSTOM\n"+
                        "   }\n"+
                        "}"
        ));
        WorkflowRun run = r.buildAndAssertSuccess(job);
        LinearScanner scan = new LinearScanner();

        FlowNode testNode = scan.findFirstMatch(run.getExecution().getCurrentHeads().get(0), new NodeStepTypePredicate("writeFile"));
        ArgumentsAction act = testNode.getPersistentAction(ArgumentsAction.class);
        Assert.assertNotNull(act);
        Assert.assertEquals("hello world", act.getArgumentValue("text"));
        Assert.assertEquals("msg.out", act.getArgumentValue("file"));

        testNode = scan.findFirstMatch(run.getExecution().getCurrentHeads().get(0), new NodeStepTypePredicate("step"));
        act = testNode.getPersistentAction(ArgumentsAction.class);
        Assert.assertNotNull(act);
        Assert.assertEquals("msg.out", act.getArgumentValue("artifacts"));
        Assert.assertEquals(Boolean.FALSE, act.getArgumentValue("fingerprint"));

        testNode = run.getExecution().getNode("7"); // Start node for EnvAction
        act = testNode.getPersistentAction(ArgumentsAction.class);
        Assert.assertNotNull(act);
        Assert.assertEquals(1, act.getArguments().size());
        Object ob = act.getArguments().get("overrides");
        Assert.assertEquals("CUSTOM=val", (String)((ArrayList) ob).get(0));
        testDeserialize(run.getExecution());
    }
}