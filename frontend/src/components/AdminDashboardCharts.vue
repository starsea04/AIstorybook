<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import { Refresh } from '@element-plus/icons-vue'
import { adminApi } from '../api'
import type { AdminAnalytics } from '../types/adminAnalytics'
import DashboardChart from './DashboardChart.vue'

const props = defineProps<{ refreshKey: number }>()
const days = ref(30)
const userId = ref<number | undefined>()
const data = ref<AdminAnalytics | null>(null)
const userOptions = ref<AdminAnalytics['users']>([])
const loading = ref(false)
const error = ref('')
let requestNumber = 0
let controller: AbortController | undefined

async function load() {
  const request = ++requestNumber
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const response = await adminApi.dashboardAnalytics({ days: days.value, userId: userId.value }, controller.signal)
    if (request !== requestNumber) return
    if (response.code !== 0 || !response.data) throw new Error(response.message || '统计数据加载失败')
    data.value = response.data as AdminAnalytics
    userOptions.value = data.value.users
  } catch (failure: any) {
    if (request !== requestNumber) return
    data.value = null
    error.value = failure?.message || '统计数据加载失败，请重试'
  } finally {
    if (request === requestNumber) loading.value = false
  }
}
watch([days, userId, () => props.refreshKey], load, { immediate: true })
onBeforeUnmount(() => { requestNumber++; controller?.abort() })

const colors = { success: '#4d83b8', failed: '#d77979', users: '#58a58d', pending: '#d7ac60', processing: '#8a7db5', other: '#a7b1bd' }
const selectedName = computed(() => userId.value ? `${userOptions.value.find(u => u.id === userId.value)?.name || '用户'} · ID ${userId.value}` : '全部用户')
const refreshed = computed(() => data.value ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(data.value.generatedAt)) : '')
const statusLabels: Record<string,string> = { SUCCESS: '成功', FAILED: '失败', PROCESSING: '生成中', PENDING: '排队中', CANCELLED: '已取消' }
const statusColors: Record<string,string> = { SUCCESS: colors.success, FAILED: colors.failed, PROCESSING: colors.processing, PENDING: colors.pending }
const chartBase: EChartsCoreOption = {
  animationDuration: 300,
  textStyle: { fontFamily: '"Noto Sans SC", sans-serif', color: '#748291', fontSize: 11 },
  aria: { enabled: true, decal: { show: true } },
}
const trendOption = computed<EChartsCoreOption>(() => ({
  ...chartBase,
  color: [colors.success, colors.failed, colors.users],
  tooltip: { trigger: 'axis', confine: true, renderMode: 'richText', axisPointer: { type: 'line' } },
  legend: { top: 0, left: 0, itemWidth: 16, textStyle: { fontSize: 11, color: '#647282' } },
  toolbox: { right: 0, top: 0, feature: { restore: { title: '重置图表' }, saveAsImage: { title: '保存图表', name: '每日图片生成趋势', pixelRatio: 2 } } },
  grid: { left: 45, right: 50, top: 55, bottom: 75, containLabel: true },
  xAxis: { type: 'category', boundaryGap: false, data: data.value?.daily.map(p => p.date), axisLabel: { formatter: (value: string) => value.slice(5), hideOverlap: true }, axisLine: { lineStyle: { color: '#dce3eb' } }, axisTick: { show: false } },
  yAxis: [
    { type: 'value', name: '图片 / 张', min: 0, minInterval: 1, splitLine: { lineStyle: { color: '#edf1f5' } } },
    { type: 'value', name: '用户 / 人', min: 0, minInterval: 1, splitLine: { show: false } },
  ],
  dataZoom: [
    { type: 'inside', zoomOnMouseWheel: 'ctrl', moveOnMouseWheel: false },
    { type: 'slider', height: 20, bottom: 10, borderColor: '#e5eaf0', fillerColor: '#4d83b81c', textStyle: { fontSize: 10 } },
  ],
  series: [
    { name: '生成成功', type: 'line', data: data.value?.daily.map(p => p.succeeded), showSymbol: days.value <= 30, symbolSize: 6, lineStyle: { width: 2.5 }, areaStyle: { opacity: 0.07 } },
    { name: '生成失败', type: 'line', data: data.value?.daily.map(p => p.failed), showSymbol: days.value <= 30, symbolSize: 5, lineStyle: { width: 2 } },
    { name: '生成用户数', type: 'line', yAxisIndex: 1, data: data.value?.daily.map(p => p.generatingUsers), showSymbol: days.value <= 30, symbolSize: 5, lineStyle: { type: 'dashed', width: 2 } },
  ],
}))
const stateOption = computed<EChartsCoreOption>(() => ({
  ...chartBase,
  tooltip: { trigger: 'item', renderMode: 'richText', confine: true, formatter: '{b}：{c} 个任务（{d}%）' },
  title: { text: data.value?.successRate == null ? '—' : `${data.value.successRate}%`, subtext: '已完成任务成功率', left: 'center', top: '32%', textStyle: { color: '#24313d', fontSize: 25, fontWeight: 650 }, subtextStyle: { color: '#748291', fontSize: 11 } },
  legend: { bottom: 0, left: 'center', itemWidth: 12, itemHeight: 9, textStyle: { color: '#748291', fontSize: 11 } },
  series: [{ type: 'pie', radius: ['48%', '67%'], center: ['50%', '43%'], label: { show: false }, labelLine: { show: false },
    emphasis: { scaleSize: 7 }, itemStyle: { borderRadius: 3, borderColor: '#fff', borderWidth: 2 },
    data: data.value?.taskStates.filter(s => s.count > 0).map(s => ({ name: statusLabels[s.status] || s.status, value: s.count, itemStyle: { color: statusColors[s.status] || colors.other } })),
  }],
}))
const rankingOption = computed<EChartsCoreOption>(() => ({
  ...chartBase,
  tooltip: { trigger: 'axis', renderMode: 'richText', confine: true, axisPointer: { type: 'shadow' } },
  grid: { top: 5, bottom: 25, left: 5, right: 40, containLabel: true },
  xAxis: { type: 'value', min: 0, minInterval: 1, splitLine: { lineStyle: { color: '#edf1f5' } } },
  yAxis: { type: 'category', inverse: true, data: data.value?.ranking.map(u => `${u.name} #${u.id}`), axisLine: { show: false }, axisTick: { show: false }, axisLabel: { width: 105, overflow: 'truncate', color: '#647282' } },
  series: [{ name: '成功图片', type: 'bar', barMaxWidth: 24, label: { show: true, position: 'right', color: '#647282' },
    data: data.value?.ranking.map(u => ({ value: u.images, userId: u.id, itemStyle: { color: u.id === userId.value ? '#e9a358' : colors.success, borderRadius: [0, 4, 4, 0] } })),
  }],
}))
function selectUser(id: number) { userId.value = id }
</script>

<template>
  <section class="dashboard-analytics" aria-label="图片生成数据分析">
    <div class="analytics-toolbar">
      <div><h2>图片生成分析</h2><p>筛选下方图表 · 上方平台总览不受筛选影响</p></div>
      <div class="analytics-filters">
        <el-radio-group v-model="days" aria-label="统计时间范围" size="small">
          <el-radio-button :value="7">近 7 天</el-radio-button><el-radio-button :value="30">近 30 天</el-radio-button><el-radio-button :value="90">近 90 天</el-radio-button>
        </el-radio-group>
        <el-select v-model="userId" placeholder="全部用户" clearable filterable aria-label="按生成用户筛选" class="analytics-user">
          <el-option v-for="user in userOptions" :key="user.id" :value="user.id" :label="`${user.name} · ID ${user.id}`" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" aria-label="刷新图表" @click="load" circle />
      </div>
    </div>
    <div v-if="error" class="analytics-error" role="alert"><span>{{ error }}</span><el-button link type="primary" @click="load">重新加载</el-button></div>
    <div v-else v-loading="loading" :aria-busy="loading" class="analytics-panels">
      <template v-if="data">
        <el-card shadow="never" class="analytics-panel trend-panel">
          <template #header><div class="chart-heading"><div><h3>每日图片生成趋势</h3><p>{{ data.startDate }} 至 {{ data.endDate }} · {{ selectedName }}</p></div><div class="period-metrics"><span>成功 <b>{{ data.succeeded }}</b> 张</span><span>失败 <b>{{ data.failed }}</b> 次</span><span>生成用户 <b>{{ data.activeUsers }}</b> 人</span></div></div></template>
          <DashboardChart :option="trendOption" :label="`每日图片生成趋势，${selectedName}，成功 ${data.succeeded} 张，失败 ${data.failed} 次，${data.activeUsers} 位用户`" class="trend-chart" />
          <p class="chart-help">悬停查看每日详情，点击图例显示或隐藏数据；拖动下方滑块缩放日期范围。</p>
          <p v-if="!data.succeeded && !data.failed" class="zero-note">所选范围暂无完成的图片任务，折线按 0 显示。</p>
        </el-card>
        <div class="analytics-bottom">
          <el-card shadow="never" class="analytics-panel">
            <template #header><div class="chart-heading"><div><h3>图片任务状态</h3><p>{{ selectedName }} · 与上方日期范围一致</p></div></div></template>
            <DashboardChart v-if="data.taskStates.some(s => s.count > 0)" :option="stateOption" label="所选用户和日期范围内的图片任务状态分布" />
            <el-empty v-else description="所选范围暂无图片任务" :image-size="65" class="chart-empty" />
          </el-card>
          <el-card shadow="never" class="analytics-panel">
            <template #header><div class="chart-heading"><div><h3>用户生成榜 · Top 10</h3><p>所选日期内全平台成功图片数 · 点击柱条筛选该用户</p></div></div></template>
            <DashboardChart v-if="data.ranking.length" :option="rankingOption" label="全平台成功图片生成用户排行，点击柱条可筛选用户" @select-user="selectUser" />
            <el-empty v-else description="所选范围暂无成功生成记录" :image-size="65" class="chart-empty" />
          </el-card>
        </div>
        <details class="analytics-definition"><summary>统计口径与数据明细 <span>更新于 {{ refreshed }}</span></summary>
          <p>北京时间。统计来源为页面插画任务，不含角色参考图；成功／失败按任务完成时的更新时间归日，每个任务计一次，重新生成的新任务单独计数。排队中／生成中按最近更新时间计入所选范围。生成用户数按成功任务用户去重，期间总人数不等于每日人数之和。成功率＝成功÷（成功＋失败），无已完成任务时显示“—”。用户榜始终展示所选日期的全平台数据。</p>
          <div class="daily-table"><table><caption>每日图片任务数据（{{ selectedName }}）</caption><thead><tr><th>日期</th><th>成功图片</th><th>失败任务</th><th>生成用户</th></tr></thead><tbody><tr v-for="point in data.daily" :key="point.date"><th scope="row">{{ point.date }}</th><td>{{ point.succeeded }}</td><td>{{ point.failed }}</td><td>{{ point.generatingUsers }}</td></tr></tbody></table></div>
        </details>
      </template>
      <el-skeleton v-else :rows="8" animated class="charts-skeleton" />
    </div>
  </section>
</template>

<style scoped>
.dashboard-analytics{margin-top:26px}.analytics-toolbar{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-bottom:16px;flex-wrap:wrap}.analytics-toolbar h2{margin:0 0 5px;font-size:17px;font-weight:650}.analytics-toolbar p,.chart-heading p{margin:0;color:#748291;font-size:11px;line-height:1.6}.analytics-filters{display:flex;align-items:center;flex-wrap:wrap;gap:9px}.analytics-user{width:195px}.analytics-panel{border:1px solid #e5eaf0;border-radius:7px;min-width:0}.analytics-panel :deep(.el-card__header){padding:16px 18px}.analytics-panel :deep(.el-card__body){padding:16px 18px}.chart-heading{display:flex;align-items:center;justify-content:space-between;gap:14px;flex-wrap:wrap}.chart-heading h3{margin:0 0 5px;font-size:14px;font-weight:650;color:#24313d}.period-metrics{display:flex;gap:16px;flex-wrap:wrap;color:#748291;font-size:11px}.period-metrics b{font-size:18px;color:#34495f;margin:0 3px;font-weight:650}.trend-chart{height:340px}.chart-help{margin:6px 0 0;color:#8a97a4;font-size:11px}.zero-note{margin:8px 0 0;color:#748291;font-size:12px}.analytics-bottom{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.25fr);gap:16px;margin-top:16px}.chart-empty{height:300px}.analytics-definition{margin-top:15px;color:#748291;font-size:11px;line-height:1.8}.analytics-definition summary{cursor:pointer}.analytics-definition summary span{float:right;color:#8a97a4}.analytics-definition p{max-width:1000px;margin:10px 0}.daily-table{max-height:320px;overflow:auto;border:1px solid #e5eaf0;background:#fff}.daily-table table{border-collapse:collapse;width:100%;font-size:12px}.daily-table caption{text-align:left;padding:9px 12px;color:#647282}.daily-table th,.daily-table td{padding:8px 12px;text-align:right;border-top:1px solid #edf1f5}.daily-table th:first-child{text-align:left}.daily-table thead th{position:sticky;top:0;background:#f5f7fa}.daily-table tbody th{font-weight:400}.analytics-error{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:18px;border:1px solid #f1c5c5;background:#fff7f7;color:#9a4343;font-size:13px}.charts-skeleton{min-height:350px;background:#fff;padding:24px}
@media(max-width:1050px){.analytics-bottom{grid-template-columns:1fr}.analytics-toolbar{align-items:flex-start;flex-direction:column}.analytics-filters{width:100%}.analytics-user{flex:1;min-width:160px;max-width:240px}}
@media(max-width:680px){.analytics-filters{gap:8px}.analytics-user{max-width:none}.analytics-panel :deep(.el-card__body){padding:12px 6px}.trend-chart{height:315px}.chart-heading{gap:8px}.period-metrics{gap:10px}.period-metrics b{font-size:16px}.chart-help,.zero-note{padding:0 8px}.analytics-definition summary span{float:none;display:block;margin-left:16px}}
</style>
