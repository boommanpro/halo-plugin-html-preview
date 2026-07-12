<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import {
  VPageHeader,
  VButton,
  VLoading,
  VEmpty,
  VSpace,
  VTag,
  Toast,
} from "@halo-dev/components";
import axios from "axios";

interface ProjectVo {
  name: string;
  displayName: string;
  originalFilename: string;
  originalSize: number;
  extractedSize: number;
  fileCount: number;
  createdAt: string;
  previewUrl: string;
}

const API_BASE = "/apis/console.api.html-preview.halo.run/v1alpha1";

const projects = ref<ProjectVo[]>([]);
const loading = ref(false);
const errorMsg = ref<string | null>(null);
const deleteTarget = ref<ProjectVo | null>(null);

// Upload / replace dialog shared state
const dialogOpen = ref(false);
const dialogMode = ref<"create" | "replace">("create");
const dialogFile = ref<File | null>(null);
const dialogDisplayName = ref("");
const dialogSlug = ref("");
const dialogSlugLocked = ref(false);
const dialogProgress = ref(0);
const dialogBusy = ref(false);
const dialogError = ref<string | null>(null);

const MAX_SIZE = 50 * 1024 * 1024; // 50 MB
const SLUG_RE = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;

const hasProjects = computed(() => projects.value.length > 0);
const totalFiles = computed(() =>
  projects.value.reduce((sum, p) => sum + p.fileCount, 0)
);
const totalSize = computed(() =>
  projects.value.reduce((sum, p) => sum + p.originalSize, 0)
);

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
  return (bytes / (1024 * 1024)).toFixed(1) + " MB";
}

function formatDate(iso: string): string {
  if (!iso) return "-";
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
    + `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function relativeTime(iso: string): string {
  if (!iso) return "";
  const diff = Date.now() - new Date(iso).getTime();
  const sec = Math.floor(diff / 1000);
  if (sec < 60) return `${sec} 秒前`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min} 分钟前`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr} 小时前`;
  const day = Math.floor(hr / 24);
  if (day < 30) return `${day} 天前`;
  return formatDate(iso);
}

async function loadProjects() {
  loading.value = true;
  errorMsg.value = null;
  try {
    const resp = await axios.get<ProjectVo[]>(`${API_BASE}/projects`);
    projects.value = Array.isArray(resp.data) ? resp.data : [];
  } catch (e: any) {
    errorMsg.value = e?.response?.data?.detail || e?.message || "加载失败";
    projects.value = [];
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  dialogMode.value = "create";
  dialogFile.value = null;
  dialogDisplayName.value = "";
  dialogSlug.value = "";
  dialogSlugLocked.value = false;
  dialogProgress.value = 0;
  dialogError.value = null;
  dialogOpen.value = true;
}

function openReplace(p: ProjectVo) {
  dialogMode.value = "replace";
  dialogFile.value = null;
  dialogDisplayName.value = p.displayName;
  dialogSlug.value = p.name;
  dialogSlugLocked.value = true;
  dialogProgress.value = 0;
  dialogError.value = null;
  dialogOpen.value = true;
}

function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement;
  const file = target.files?.[0] || null;
  if (!file) return;
  if (!file.name.toLowerCase().endsWith(".zip")) {
    dialogError.value = "只接受 .zip 文件";
    return;
  }
  if (file.size > MAX_SIZE) {
    dialogError.value = "zip 不能超过 50 MB";
    return;
  }
  dialogFile.value = file;
  if (!dialogDisplayName.value) {
    dialogDisplayName.value = file.name.replace(/\.zip$/i, "");
  }
  // 创建模式下:文件名若符合 slug 规则,自动填入 slug 字段
  if (dialogMode.value === "create" && !dialogSlug.value) {
    const guess = file.name
      .toLowerCase()
      .replace(/\.zip$/i, "")
      .replace(/[^a-z0-9-]+/g, "-")
      .replace(/^-+|-+$/g, "");
    if (SLUG_RE.test(guess)) {
      dialogSlug.value = guess;
    }
  }
  dialogError.value = null;
}

function onSlugInput(e: Event) {
  if (dialogSlugLocked.value) return;
  const v = (e.target as HTMLInputElement).value;
  // 实时规范化:转小写、剔除非法字符
  const cleaned = v
    .toLowerCase()
    .replace(/[^a-z0-9-]/g, "")
    .replace(/-{2,}/g, "-");
  dialogSlug.value = cleaned;
}

function validateDialog(): string | null {
  if (!dialogFile.value) return "请选择 zip 文件";
  if (dialogSlug.value && !SLUG_RE.test(dialogSlug.value)) {
    return "slug 必须为 2-63 字符,仅小写字母/数字/连字符,首尾为字母或数字";
  }
  return null;
}

async function submitDialog() {
  const err = validateDialog();
  if (err) {
    dialogError.value = err;
    return;
  }
  dialogBusy.value = true;
  dialogProgress.value = 0;
  dialogError.value = null;
  try {
    const form = new FormData();
    form.append("file", dialogFile.value!);
    if (dialogDisplayName.value) {
      form.append("displayName", dialogDisplayName.value);
    }
    if (dialogMode.value === "create" && dialogSlug.value) {
      form.append("slug", dialogSlug.value);
    }
    const url = dialogMode.value === "create"
      ? `${API_BASE}/projects`
      : `${API_BASE}/projects/${dialogSlug.value}/replace`;
    const method = dialogMode.value === "create" ? "post" : "put";
    await axios.request({
      method,
      url,
      data: form,
      onUploadProgress: (e) => {
        if (e.total) {
          dialogProgress.value = Math.round((e.loaded / e.total) * 100);
        }
      },
    });
    Toast.success(dialogMode.value === "create" ? "上传成功" : "已更新");
    dialogOpen.value = false;
    await loadProjects();
  } catch (e: any) {
    dialogError.value =
      e?.response?.data?.detail || e?.response?.data?.message || e?.message
      || (dialogMode.value === "create" ? "上传失败" : "更新失败");
  } finally {
    dialogBusy.value = false;
  }
}

function openPreview(p: ProjectVo) {
  const fullUrl = window.location.origin + p.previewUrl;
  window.open(fullUrl, "_blank");
}

function copyPreviewUrl(p: ProjectVo) {
  const fullUrl = window.location.origin + p.previewUrl;
  navigator.clipboard.writeText(fullUrl).then(
    () => Toast.success("已复制预览链接"),
    () => Toast.error("复制失败")
  );
}

function openDelete(p: ProjectVo) {
  deleteTarget.value = p;
}

async function confirmDelete() {
  if (!deleteTarget.value) return;
  const name = deleteTarget.value.name;
  try {
    await axios.delete(`${API_BASE}/projects/${name}`);
    Toast.success("已删除");
    deleteTarget.value = null;
    await loadProjects();
  } catch (e: any) {
    Toast.error(e?.response?.data?.detail || e?.message || "删除失败");
  }
}

onMounted(loadProjects);
</script>

<template>
  <VPageHeader title="HTML 项目预览">
    <template #actions>
      <VButton type="primary" @click="openCreate">+ 上传项目</VButton>
    </template>
  </VPageHeader>

  <div class="html-preview-pm">
    <!-- Stats summary -->
    <div v-if="hasProjects && !loading && !errorMsg" class="stats-bar">
      <div class="stat-card">
        <span class="stat-value">{{ projects.length }}</span>
        <span class="stat-label">项目总数</span>
      </div>
      <div class="stat-card">
        <span class="stat-value">{{ totalFiles }}</span>
        <span class="stat-label">文件总数</span>
      </div>
      <div class="stat-card">
        <span class="stat-value">{{ formatSize(totalSize) }}</span>
        <span class="stat-label">占用空间</span>
      </div>
    </div>

    <VLoading v-if="loading" />

    <VEmpty v-else-if="errorMsg" title="加载失败" :description="errorMsg">
      <template #actions>
        <VButton @click="loadProjects">重试</VButton>
      </template>
    </VEmpty>

    <VEmpty
      v-else-if="!hasProjects"
      title="暂无项目"
      description="上传一个 zip 即可获得 /preview/{slug}/ 公开访问链接"
    >
      <template #actions>
        <VButton type="primary" @click="openCreate">立即上传</VButton>
      </template>
    </VEmpty>

    <div v-else class="project-grid">
      <div v-for="p in projects" :key="p.name" class="project-card">
        <div class="card-header">
          <div class="card-title-wrap">
            <div class="card-title" :title="p.displayName">
              {{ p.displayName }}
            </div>
            <div class="card-slug" :title="'/' + p.name + '/'">
              <span class="slug-hash">#</span>{{ p.name }}
            </div>
          </div>
          <div class="card-menu">
            <button class="icon-btn" title="预览" @click="openPreview(p)">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                <circle cx="12" cy="12" r="3"/>
              </svg>
            </button>
            <button class="icon-btn" title="复制链接" @click="copyPreviewUrl(p)">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
              </svg>
            </button>
            <button class="icon-btn" title="更新" @click="openReplace(p)">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 12a9 9 0 1 1-3-6.7"/>
                <polyline points="21 4 21 9 16 9"/>
              </svg>
            </button>
            <button class="icon-btn danger" title="删除" @click="openDelete(p)">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="3 6 5 6 21 6"/>
                <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
                <path d="M10 11v6M14 11v6"/>
                <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
              </svg>
            </button>
          </div>
        </div>

        <div class="card-meta">
          <div class="meta-row">
            <span class="meta-key">预览地址</span>
            <code class="meta-val mono" :title="p.previewUrl">{{ p.previewUrl }}</code>
          </div>
          <div class="meta-row">
            <span class="meta-key">原始文件</span>
            <span class="meta-val" :title="p.originalFilename">{{ p.originalFilename }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-key">上传时间</span>
            <span class="meta-val">{{ formatDate(p.createdAt) }}</span>
            <span class="meta-sub">{{ relativeTime(p.createdAt) }}</span>
          </div>
        </div>

        <div class="card-footer">
          <VSpace spacing="sm">
            <VTag>{{ p.fileCount }} 个文件</VTag>
            <VTag>{{ formatSize(p.originalSize) }}</VTag>
            <VTag v-if="p.extractedSize">{{ formatSize(p.extractedSize) }} 解压</VTag>
          </VSpace>
        </div>
      </div>
    </div>
  </div>

  <!-- Upload / Replace dialog -->
  <div v-if="dialogOpen" class="dialog-mask" @click.self="dialogOpen = false">
    <div class="dialog-box">
      <div class="dialog-head">
        <h3>{{ dialogMode === "create" ? "上传新项目" : "更新项目" }}</h3>
        <button class="icon-btn" @click="dialogOpen = false">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>

      <div class="form-row">
        <label>zip 文件 (≤ 50 MB) <span class="req">*</span></label>
        <label class="file-picker">
          <input type="file" accept=".zip" @change="onFileChange" />
          <span class="file-picker-text">
            {{ dialogFile ? dialogFile.name : "点击选择 .zip 文件" }}
          </span>
          <span v-if="dialogFile" class="file-picker-size">
            {{ formatSize(dialogFile.size) }}
          </span>
        </label>
      </div>

      <div class="form-row">
        <label>路径 slug <span class="hint">留空则随机生成</span></label>
        <div class="slug-input-wrap">
          <span class="slug-prefix">/preview/</span>
          <input
            v-model="dialogSlug"
            type="text"
            :disabled="dialogSlugLocked"
            :class="['slug-input', { locked: dialogSlugLocked }]"
            placeholder="留空 = 随机"
            @input="onSlugInput"
          />
          <span class="slug-suffix">/</span>
        </div>
        <small v-if="dialogSlugLocked" class="slug-locked-hint">
          更新模式下 slug 不可修改
        </small>
        <small v-else class="slug-rules">
          规则:2-63 字符,小写字母 / 数字 / 连字符,首尾为字母或数字
        </small>
      </div>

      <div class="form-row">
        <label>显示名称 <span class="hint">可选,默认取 zip 文件名</span></label>
        <input v-model="dialogDisplayName" type="text" placeholder="例如:产品介绍页" />
      </div>

      <div v-if="dialogBusy" class="progress-bar">
        <div class="progress-fill" :style="{ width: dialogProgress + '%' }" />
        <span>{{ dialogProgress }}%</span>
      </div>

      <p v-if="dialogError" class="error-msg">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
        </svg>
        {{ dialogError }}
      </p>

      <div class="dialog-actions">
        <VButton :disabled="dialogBusy" @click="dialogOpen = false">取消</VButton>
        <VButton type="primary" :disabled="dialogBusy || !dialogFile" @click="submitDialog">
          {{ dialogBusy
            ? (dialogMode === "create" ? "上传中..." : "更新中...")
            : (dialogMode === "create" ? "上传" : "确认更新") }}
        </VButton>
      </div>
    </div>
  </div>

  <!-- Delete confirm dialog -->
  <div v-if="deleteTarget" class="dialog-mask" @click.self="deleteTarget = null">
    <div class="dialog-box danger">
      <div class="dialog-head">
        <h3>确认删除</h3>
      </div>
      <p class="delete-msg">
        将删除项目
        <b>{{ deleteTarget.displayName }}</b>
        (<code>{{ deleteTarget.name }}</code>)。
      </p>
      <p class="delete-warn">
        删除后预览链接将立即失效,且不可恢复。
      </p>
      <div class="dialog-actions">
        <VButton @click="deleteTarget = null">取消</VButton>
        <VButton type="danger" @click="confirmDelete">确认删除</VButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
.html-preview-pm {
  padding: 16px 24px 40px;
}

/* Stats bar */
.stats-bar {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}
.stat-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 16px 20px;
  background: var(--color-bg, #fff);
  border: 1px solid var(--color-divider, #e5e7eb);
  border-radius: 8px;
}
.stat-value {
  font-size: 22px;
  font-weight: 600;
  color: var(--color-text, #111827);
}
.stat-label {
  font-size: 12px;
  color: var(--color-text-secondary, #6b7280);
}

/* Project grid */
.project-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 16px;
}
.project-card {
  display: flex;
  flex-direction: column;
  background: var(--color-bg, #fff);
  border: 1px solid var(--color-divider, #e5e7eb);
  border-radius: 10px;
  padding: 16px;
  transition: box-shadow 0.2s, border-color 0.2s, transform 0.15s;
}
.project-card:hover {
  border-color: var(--color-primary, #3b82f6);
  box-shadow: 0 6px 18px rgba(59, 130, 246, 0.12);
  transform: translateY(-2px);
}

.card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.card-title-wrap {
  flex: 1;
  min-width: 0;
}
.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text, #111827);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.card-slug {
  margin-top: 2px;
  font-size: 12px;
  color: var(--color-text-secondary, #6b7280);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.slug-hash {
  color: var(--color-primary, #3b82f6);
  margin-right: 2px;
}

.card-menu {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--color-text-secondary, #6b7280);
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.icon-btn:hover {
  background: var(--color-gray-100, #f3f4f6);
  color: var(--color-text, #111827);
}
.icon-btn.danger:hover {
  background: #fef2f2;
  color: #ef4444;
}

.card-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 0;
  border-top: 1px dashed var(--color-divider, #e5e7eb);
  border-bottom: 1px dashed var(--color-divider, #e5e7eb);
  margin-bottom: 12px;
}
.meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.meta-key {
  flex: 0 0 64px;
  color: var(--color-text-secondary, #6b7280);
}
.meta-val {
  flex: 1;
  color: var(--color-text, #111827);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.meta-sub {
  color: var(--color-text-secondary, #9ca3af);
  font-size: 11px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  background: var(--color-gray-100, #f3f4f6);
  padding: 1px 6px;
  border-radius: 4px;
}

.card-footer {
  margin-top: auto;
}

/* Dialog */
.dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}
.dialog-box {
  background: var(--color-bg, #fff);
  padding: 24px 28px;
  border-radius: 12px;
  min-width: 440px;
  max-width: 560px;
  box-shadow: 0 20px 48px rgba(0, 0, 0, 0.25);
}
.dialog-box.danger {
  border-top: 3px solid #ef4444;
}
.dialog-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.dialog-box h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.dialog-box .icon-btn {
  width: 30px;
  height: 30px;
}

.form-row {
  margin-bottom: 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.form-row label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text, #111827);
}
.req {
  color: #ef4444;
}
.hint {
  font-weight: 400;
  font-size: 11px;
  color: var(--color-text-secondary, #9ca3af);
  margin-left: 4px;
}
.form-row input[type=text] {
  padding: 8px 12px;
  border: 1px solid var(--color-border, #d4d4d8);
  border-radius: 6px;
  font-size: 13px;
  background: var(--color-bg, #fff);
  color: var(--color-text, #111827);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.form-row input[type=text]:focus {
  outline: none;
  border-color: var(--color-primary, #3b82f6);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
}
.form-row input[type=text]:disabled {
  background: var(--color-gray-100, #f3f4f6);
  cursor: not-allowed;
}

.file-picker {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px dashed var(--color-border, #d4d4d8);
  border-radius: 6px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.file-picker:hover {
  border-color: var(--color-primary, #3b82f6);
  background: rgba(59, 130, 246, 0.04);
}
.file-picker input[type=file] {
  display: none;
}
.file-picker-text {
  flex: 1;
  font-size: 13px;
  color: var(--color-text, #111827);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.file-picker-size {
  font-size: 11px;
  color: var(--color-text-secondary, #6b7280);
  background: var(--color-gray-100, #f3f4f6);
  padding: 2px 8px;
  border-radius: 10px;
}

.slug-input-wrap {
  display: flex;
  align-items: center;
  border: 1px solid var(--color-border, #d4d4d8);
  border-radius: 6px;
  overflow: hidden;
  background: var(--color-bg, #fff);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.slug-input-wrap:focus-within {
  border-color: var(--color-primary, #3b82f6);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
}
.slug-prefix,
.slug-suffix {
  padding: 0 10px;
  font-size: 13px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--color-text-secondary, #6b7280);
  background: var(--color-gray-100, #f3f4f6);
  align-self: stretch;
  display: flex;
  align-items: center;
}
.slug-input {
  flex: 1;
  border: none;
  padding: 8px 10px;
  font-size: 13px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  background: transparent;
  color: var(--color-text, #111827);
}
.slug-input:focus {
  outline: none;
}
.slug-input.locked {
  color: var(--color-text-secondary, #6b7280);
}
.slug-locked-hint {
  color: #f59e0b;
  font-size: 11px;
}
.slug-rules {
  color: var(--color-text-secondary, #9ca3af);
  font-size: 11px;
}

.progress-bar {
  position: relative;
  height: 18px;
  background: var(--color-gray-100, #e4e4e7);
  border-radius: 4px;
  overflow: hidden;
  margin: 8px 0;
}
.progress-fill {
  position: absolute;
  top: 0;
  left: 0;
  height: 100%;
  background: linear-gradient(90deg, #3b82f6, #60a5fa);
  transition: width 0.2s;
}
.progress-bar span {
  position: absolute;
  right: 8px;
  top: 0;
  line-height: 18px;
  font-size: 11px;
  font-weight: 500;
  color: var(--color-text, #111827);
}

.error-msg {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #ef4444;
  font-size: 12px;
  margin: 8px 0;
  padding: 8px 10px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 6px;
}

.delete-msg code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  background: var(--color-gray-100, #f3f4f6);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 12px;
}
.delete-warn {
  margin-top: 8px;
  font-size: 12px;
  color: #ef4444;
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 18px;
}
</style>
