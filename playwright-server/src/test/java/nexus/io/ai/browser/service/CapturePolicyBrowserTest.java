package nexus.io.ai.browser.service;

import static org.junit.Assert.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.*;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import nexus.io.model.body.RespBodyVo;

/** Real isolated Chrome screenshots with faults only at the screenshot boundary. */
public class CapturePolicyBrowserTest {
  private static PlaywrightService service;
  private static Long id;
  private static Path output;
  @BeforeClass public static void start() throws Exception {
    output=Files.createTempDirectory("capture-policy-evidence");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR,Files.createTempDirectory("capture-policy-profile").toString());
    ChromeBrowser.resetForTests();service=new PlaywrightService();id=service.start(null,true);
  }
  @AfterClass public static void stop() {
    if(id!=null)service.close(id);
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIR);ChromeBrowser.resetForTests();
  }
  @Before public void page() {
    BrowserInstance inst=service.getInstance(id);inst.captureFailures.set(0);inst.captureCooldownUntil=0;inst.captureFailureReason=null;
    inst.page.setViewportSize(640,480);
    inst.page.setContent("<html><body style='height:1600px'><button id='button'>Capture fixture</button></body></html>");
  }
  private Page failing(Page page,boolean onlyFull) {
    return (Page)Proxy.newProxyInstance(Page.class.getClassLoader(),new Class<?>[]{Page.class},(proxy,method,args)->{
      if(method.getName().equals("screenshot") && (!onlyFull || Boolean.TRUE.equals(((Page.ScreenshotOptions)args[0]).fullPage)))
        throw new PlaywrightException("Timeout injected at screenshot boundary");
      try{return method.invoke(page,args);}catch(InvocationTargetException error){throw error.getCause();}
    });
  }
  @Test public void circuitFastFailureAndForceRecoveryPreserveCommandMetadata() throws Exception {
    BrowserInstance inst=service.getInstance(id);Page original=inst.page;inst.page=failing(original,false);
    try {for(int i=0;i<PlaywrightService.captureFailThreshold();i++)assertNotNull(service.capture(inst).get("screenshot_error"));}
    finally {inst.page=original;}
    ActionService actions=new ActionService(service);JSONObject args=new JSONObject();
    args.put("path",output.resolve("recovery.png").toString());args.put("timeoutMs",8000);
    RespBodyVo blocked=actions.execute(id,"screenshot",args);assertFalse(blocked.isOk());Kv data=(Kv)blocked.getData();
    assertEquals("CAPTURE_CIRCUIT_OPEN",data.get("errorCode"));assertTrue(((Number)data.get("retryAfterMs")).longValue()>1000);
    assertEquals(0,((Kv)data.get("capture")).getInt("attempts").intValue());assertFalse(Files.exists(output.resolve("recovery.png")));
    args.put("force",true);RespBodyVo result=actions.execute(id,"screenshot",args);assertTrue(result.getMsg(),result.isOk());
    data=(Kv)result.getData();assertNull(data.get("unknownParams"));assertEquals(false,data.get("capture_degraded"));
    assertNotNull(data.get("retryBudget"));assertEquals(640,ImageIO.read(output.resolve("recovery.png").toFile()).getWidth());
    assertNotNull(service.capture(inst).get("screenshot_path"));
  }
  @Test public void fullPageFallbackReallySavesOnlyViewport() throws Exception {
    BrowserInstance inst=service.getInstance(id);Page original=inst.page;inst.page=failing(original,true);
    RespBodyVo result;
    try {result=service.screenshot(id,output.resolve("fallback.png").toString(),true,null,null,null,null,null,null,false,8000,false,true);}
    finally {inst.page=original;}
    assertTrue(result.getMsg(),result.isOk());Kv data=(Kv)result.getData();
    assertEquals(false,data.get("fullPageCaptured"));assertEquals(true,data.get("fallbackUsed"));
    assertEquals("viewport",((Kv)data.get("capture")).get("actualMode"));
    assertEquals(480,ImageIO.read(output.resolve("fallback.png").toFile()).getHeight());
    RespBodyVo full=service.screenshot(id,output.resolve("full.png").toString(),true,null,null,null,null,null,null,false);
    assertTrue(full.getMsg(),full.isOk());assertEquals(true,((Kv)full.getData()).get("fullPageCaptured"));
    assertTrue(ImageIO.read(output.resolve("full.png").toFile()).getHeight()>480);
  }
  @Test public void elementScreenshotAcceptsSameBudgetOptions() {
    JSONObject args=new JSONObject();args.put("selector","#button");args.put("path",output.resolve("element.png").toString());
    args.put("timeoutMs",8000);args.put("force",false);
    RespBodyVo result=new ActionService(service).execute(id,"get_element_screenshot",args);assertTrue(result.getMsg(),result.isOk());
    Kv data=(Kv)result.getData();assertNull(data.get("unknownParams"));assertEquals("element",((Kv)data.get("capture")).get("actualMode"));
    assertTrue(Files.exists(output.resolve("element.png")));
  }
  @Test public void failedEvidenceDoesNotReplayOrFailSuccessfulClick() {
    BrowserInstance inst=service.getInstance(id);Page original=inst.page;
    original.evaluate("() => { window.captureClicks=0; document.querySelector('#button').onclick=()=>window.captureClicks++; }");
    inst.page=failing(original,false);
    try {
      JSONObject args=new JSONObject();args.put("selector","#button");args.put("mode","native");
      RespBodyVo result=new ActionService(service).execute(id,"click_element_by_selector",args);
      assertTrue(result.getMsg(),result.isOk());Kv data=(Kv)result.getData();
      assertNotNull(data.get("screenshot_error"));assertNull(data.get("errorCode"));
      assertEquals(1,((Number)original.evaluate("() => window.captureClicks")).intValue());
    } finally {inst.page=original;}
  }
}
