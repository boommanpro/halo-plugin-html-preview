import { definePlugin } from "@halo-dev/console-shared";
import { markRaw } from "vue";
import ProjectManagementTab from "./views/ProjectManagementTab.vue";

export default definePlugin({
  routes: [],
  extensionPoints: {
    "plugin:self:tabs:create": () => [
      {
        id: "html-preview:project-management",
        label: "项目管理",
        component: markRaw(ProjectManagementTab),
      },
    ],
  },
});
