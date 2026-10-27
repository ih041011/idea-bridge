/**
 * Throwaway target for verifying the bridge: it has to be openable, analysable, editable and runnable.
 * 中文注释用来验证 UTF-8 往返，字符：你好，世界 “quoted” — em dash.
 */
public class Hello {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("hello from the bridge demo");
        Thread.sleep(200);
        System.out.println("bridge run finished");
    }
}
