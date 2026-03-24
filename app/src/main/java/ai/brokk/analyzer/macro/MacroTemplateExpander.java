package ai.brokk.analyzer.macro;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.github.mustachejava.reflect.ReflectionObjectHandler;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

/**
 * Expands Mustache templates using a provided context map.
 */
public final class MacroTemplateExpander {
    private static final MustacheFactory MUSTACHE_FACTORY;

    static {
        DefaultMustacheFactory mf = new DefaultMustacheFactory();
        // Disable HTML escaping for code templates
        mf.setObjectHandler(new ReflectionObjectHandler());
        MUSTACHE_FACTORY = mf;
    }

    private MacroTemplateExpander() {}

    /**
     * Expands the given Mustache template string using the provided context variables.
     *
     * @param template the Mustache template string
     * @param context the variables to substitute into the template
     * @return the expanded string
     */
    public static String expand(String template, Map<String, Object> context) {
        Mustache mustache = MUSTACHE_FACTORY.compile(new StringReader(template), "macro_template");
        StringWriter writer = new StringWriter();
        mustache.execute(writer, context);
        return writer.toString();
    }
}
