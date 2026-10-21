export function chartOption(completed, active) {
  return { grid:{top:12,right:12,bottom:24,left:32}, xAxis:{type:'category',data:['进行中','已完成']},
    yAxis:{type:'value',minInterval:1}, series:[{type:'bar',barWidth:28,data:[active,completed],itemStyle:{color:'#148b78'}}], tooltip:{trigger:'axis'} }
}
