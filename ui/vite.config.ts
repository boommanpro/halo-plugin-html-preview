import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { HaloUIPluginBundlerKit } from "@halo-dev/ui-plugin-bundler-kit";

export default defineConfig({
  plugins: [vue(), HaloUIPluginBundlerKit()],
});
