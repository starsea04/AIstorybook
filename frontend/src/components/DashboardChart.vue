<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { init, use, type EChartsCoreOption, type ECharts } from 'echarts/core'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, ToolboxComponent, TitleComponent, AriaComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'

use([LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, ToolboxComponent, TitleComponent, AriaComponent, SVGRenderer])
const props = defineProps<{ option: EChartsCoreOption; label: string }>()
const emit = defineEmits<{ selectUser: [id: number] }>()
const host = ref<HTMLElement>()
let chart: ECharts | undefined
let observer: ResizeObserver | undefined
let frame = 0

onMounted(() => {
  if (!host.value) return
  chart = init(host.value, undefined, { renderer: 'svg', locale: 'ZH' })
  chart.setOption(props.option)
  chart.on('click', event => {
    const point = event.data as { userId?: number } | undefined
    if (point?.userId) emit('selectUser', point.userId)
  })
  observer = new ResizeObserver(() => {
    cancelAnimationFrame(frame)
    frame = requestAnimationFrame(() => chart?.resize())
  })
  observer.observe(host.value)
})
watch(() => props.option, option => chart?.setOption(option, { notMerge: true }))
onBeforeUnmount(() => {
  observer?.disconnect()
  cancelAnimationFrame(frame)
  chart?.dispose()
  chart = undefined
})
</script>

<template><div ref="host" class="dashboard-chart" role="img" :aria-label="label"></div></template>
<style scoped>.dashboard-chart{width:100%;height:300px;min-width:0}</style>
