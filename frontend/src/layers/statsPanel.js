export function chartOption(completed, active) {
  return { grid:{top:12,right:12,bottom:24,left:32}, xAxis:{type:'category',data:['传统配送','智能配送'],axisLine:{lineStyle:{color:'#cdd6d4'}},axisLabel:{color:'#687a80'}},
    yAxis:{type:'value',axisLine:{show:false},splitLine:{lineStyle:{color:'#e8edec'}},axisLabel:{color:'#687a80'}},
    series:[{type:'bar',barWidth:28,data:[72,41],itemStyle:{color:({dataIndex})=>dataIndex ? '#148b78':'#9faeae',borderRadius:[3,3,0,0]}}],
    tooltip:{trigger:'axis',formatter:'{b}<br/>示意能耗：{c} 单位',backgroundColor:'#22383b',textStyle:{color:'#f8fbfa'}} }
}
