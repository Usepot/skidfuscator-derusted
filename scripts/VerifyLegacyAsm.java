import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;

/**
 * Compile and run with the actual legacy ASM jar and Java 8, not the compiler's
 * shaded modern ASM. Validates ALL selected and newly generated classes after
 * two successive identity/descriptor remapping passes, in isolated classloaders.
 * Does not initialize client classes or connect to a server.
 *
 * Usage: VerifyLegacyAsm input.jar candidate.jar owned-classes.txt libraries.txt
 */
public final class VerifyLegacyAsm {
    private static byte[] bytes(InputStream stream) throws IOException {
        try(InputStream input=stream; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int count;
            while((count=input.read(buffer))!=-1) out.write(buffer,0,count);
            return out.toByteArray();
        }
    }
    private static Set<String> classes(JarFile jar) {
        Set<String> names=new HashSet<>();
        Enumeration<JarEntry> entries=jar.entries();
        while(entries.hasMoreElements()) {
            String name=entries.nextElement().getName();
            if(name.endsWith(".class")) names.add(name.substring(0,name.length()-6));
        }
        return names;
    }
    private static byte[] remap(byte[] input, boolean seeded) {
        ClassReader reader=new ClassReader(input);
        ClassWriter writer=seeded?new ClassWriter(reader,0):new ClassWriter(0);
        reader.accept(new RemappingClassAdapter(writer,new Remapper() {}),ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }
    private static final class Loader extends URLClassLoader {
        private final Map<String,byte[]> transformed;
        Loader(URL[] urls,Map<String,byte[]> transformed) { super(urls,null); this.transformed=transformed; }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] content=transformed.get(name.replace('.','/'));
            return content==null?super.findClass(name):defineClass(name,content,0,content.length);
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4) throw new IllegalArgumentException("input.jar candidate.jar owned-classes.txt libraries.txt");
        SortedSet<String> selected=new TreeSet<>();
        for(String line:Files.readAllLines(Paths.get(args[2]),StandardCharsets.UTF_8)) {
            String name=line.trim();
            if(!name.isEmpty()&&!name.startsWith("#")) selected.add(name.replace('.','/'));
        }
        List<URL> urls=new ArrayList<>(); urls.add(new File(args[1]).toURI().toURL());
        for(String line:Files.readAllLines(Paths.get(args[3]),StandardCharsets.UTF_8)) {
            String path=line.trim(); if(!path.isEmpty()) urls.add(new File(path).toURI().toURL());
        }
        Map<String,byte[]> original=new TreeMap<>(); int generated;
        try(JarFile input=new JarFile(args[0]); JarFile candidate=new JarFile(args[1])) {
            Set<String> newClasses=classes(candidate); newClasses.removeAll(classes(input)); generated=newClasses.size(); selected.addAll(newClasses);
            for(String name:selected) {
                JarEntry entry=candidate.getJarEntry(name+".class");
                if(entry==null) throw new IllegalStateException("Missing selected output class: "+name);
                original.put(name,bytes(candidate.getInputStream(entry)));
            }
        }
        if(selected.isEmpty()) throw new IllegalStateException("No classes selected; refusing a vacuous pass");
        System.out.println("Legacy ASM implementation="+ClassReader.class.getProtectionDomain().getCodeSource().getLocation());
        int failures=0,checks=0;
        for(boolean seeded:new boolean[]{false,true}) {
            Map<String,byte[]> current=original;
            for(int pass=1;pass<=2;pass++) {
                Map<String,byte[]> next=new TreeMap<>(); int modeFailures=0;
                for(Map.Entry<String,byte[]> entry:current.entrySet()) {
                    try { next.put(entry.getKey(),remap(entry.getValue(),seeded)); }
                    catch(Throwable failure) {
                        modeFailures++; System.err.println("REWRITE FAIL seeded="+seeded+" pass="+pass+" "+entry.getKey()+": "+failure);
                    }
                }
                try(Loader loader=new Loader(urls.toArray(new URL[0]),next)) {
                    for(String name:next.keySet()) {
                        try {
                            Class<?> type=Class.forName(name.replace('/','.'),false,loader);
                            type.getDeclaredConstructors(); type.getDeclaredMethods(); type.getDeclaredFields(); checks++;
                        } catch(Throwable failure) {
                            modeFailures++; System.err.println("VERIFY FAIL seeded="+seeded+" pass="+pass+" "+name+": "+failure);
                            Throwable cause=failure.getCause(); if(cause!=null) System.err.println("  caused by "+cause);
                        }
                    }
                }
                System.out.println("seeded="+seeded+" pass="+pass+" selected="+selected.size()+" rewritten="+next.size()+" failures="+modeFailures);
                failures+=modeFailures; current=next;
                if(modeFailures!=0) break; // malformed intermediate output is never used for another pass
            }
        }
        if(failures!=0) throw new IllegalStateException("Legacy ASM verification failed: "+failures);
        System.out.println("VERIFIED LEGACY ASM: selected="+selected.size()+" generated="+generated+" JVM class checks="+checks+" failures=0");
    }
}
