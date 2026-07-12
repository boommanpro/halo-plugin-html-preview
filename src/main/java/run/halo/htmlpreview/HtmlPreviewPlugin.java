package run.halo.htmlpreview;

import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.htmlpreview.extension.HtmlProject;

/**
 * Plugin main class to manage the lifecycle of the html-preview plugin.
 */
@Component
public class HtmlPreviewPlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public HtmlPreviewPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(HtmlProject.class);
        System.out.println("[html-preview] 插件启动成功！");
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(HtmlProject.class));
        System.out.println("[html-preview] 插件停止！");
    }
}
