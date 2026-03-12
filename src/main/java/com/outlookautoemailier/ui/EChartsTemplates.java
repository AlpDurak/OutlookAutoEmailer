package com.outlookautoemailier.ui;

/**
 * Factory for Apache ECharts HTML page templates.
 *
 * <p>Each method returns a complete HTML page string that can be loaded
 * into a JavaFX {@link javafx.scene.web.WebView} via
 * {@code webEngine.loadContent(html)}.  Every page exposes an
 * {@code updateChart(data...)} JavaScript function that can be called
 * from Java via {@code webEngine.executeScript()}.</p>
 *
 * <p>All charts use the ECharts 5 CDN and share a consistent dark/modern
 * color palette matching the app's design system.</p>
 */
public final class EChartsTemplates {

    private EChartsTemplates() {}

    private static final String ECHARTS_CDN =
            "https://cdn.jsdelivr.net/npm/echarts@5/dist/echarts.min.js";

    private static final String COMMON_STYLE = """
            * { margin:0; padding:0; box-sizing:border-box; }
            body { background:#ffffff; font-family:"Segoe UI",Arial,sans-serif; }
            #chart { width:100%; height:100%; min-height:220px; }
            """;

    // ── Delivery Timeline (Line Chart) ───────────────────────────────────────

    /**
     * Line chart showing Sent and Failed counts per date.
     * Call: {@code updateChart(dates[], sent[], failed[], opens[])}
     */
    public static String deliveryTimelineHtml() {
        return """
                <!DOCTYPE html><html><head><meta charset="UTF-8">
                <style>%s</style>
                <script src="%s"></script>
                </head><body><div id="chart"></div>
                <script>
                var chart = echarts.init(document.getElementById('chart'));
                function updateChart(dates, sent, failed, opens) {
                  chart.setOption({
                    backgroundColor:'#ffffff',
                    tooltip:{trigger:'axis'},
                    legend:{data:['Sent','Failed','Opens'],textStyle:{color:'#6b7280',fontSize:11},top:4},
                    grid:{left:50,right:16,top:40,bottom:30},
                    xAxis:{type:'category',data:dates,axisLabel:{color:'#6b7280',fontSize:10,rotate:30},
                           axisLine:{lineStyle:{color:'#dde3ec'}},axisTick:{show:false}},
                    yAxis:{type:'value',axisLabel:{color:'#6b7280',fontSize:11},
                           splitLine:{lineStyle:{color:'#eef2f7'}},axisLine:{show:false}},
                    series:[
                      {name:'Sent',type:'line',data:sent,smooth:true,
                       lineStyle:{color:'#2563eb',width:2},itemStyle:{color:'#2563eb'},
                       areaStyle:{color:{type:'linear',x:0,y:0,x2:0,y2:1,
                         colorStops:[{offset:0,color:'rgba(37,99,235,0.25)'},{offset:1,color:'rgba(37,99,235,0.02)'}]}}},
                      {name:'Failed',type:'line',data:failed,smooth:true,
                       lineStyle:{color:'#ef4444',width:2},itemStyle:{color:'#ef4444'}},
                      {name:'Opens',type:'line',data:opens,smooth:true,
                       lineStyle:{color:'#16a34a',width:2,type:'dashed'},itemStyle:{color:'#16a34a'}}
                    ]
                  });
                }
                window.addEventListener('resize',function(){chart.resize();});
                </script></body></html>
                """.formatted(COMMON_STYLE, ECHARTS_CDN);
    }

    // ── Hourly Distribution (Bar Chart) ──────────────────────────────────────

    /**
     * Bar chart showing email count by hour of day (0-23).
     * Call: {@code updateChart(counts[])} where counts is int[24].
     */
    public static String hourlyDistributionHtml() {
        return """
                <!DOCTYPE html><html><head><meta charset="UTF-8">
                <style>%s</style>
                <script src="%s"></script>
                </head><body><div id="chart"></div>
                <script>
                var chart = echarts.init(document.getElementById('chart'));
                function updateChart(counts) {
                  var hours = [];
                  for(var i=0;i<24;i++) hours.push(i+':00');
                  chart.setOption({
                    backgroundColor:'#ffffff',
                    tooltip:{trigger:'axis',axisPointer:{type:'shadow'}},
                    grid:{left:44,right:16,top:20,bottom:30},
                    xAxis:{type:'category',data:hours,axisLabel:{color:'#6b7280',fontSize:10},
                           axisLine:{lineStyle:{color:'#dde3ec'}},axisTick:{show:false}},
                    yAxis:{type:'value',axisLabel:{color:'#6b7280',fontSize:11},
                           splitLine:{lineStyle:{color:'#eef2f7'}},axisLine:{show:false}},
                    series:[{type:'bar',data:counts,barMaxWidth:20,
                      itemStyle:{color:{type:'linear',x:0,y:0,x2:0,y2:1,
                        colorStops:[{offset:0,color:'#3b82f6'},{offset:1,color:'#1d4ed8'}]},
                        borderRadius:[3,3,0,0]},
                      emphasis:{itemStyle:{color:'#1e40af'}}}]
                  });
                }
                window.addEventListener('resize',function(){chart.resize();});
                </script></body></html>
                """.formatted(COMMON_STYLE, ECHARTS_CDN);
    }

    // ── Failure Category (Donut/Pie Chart) ───────────────────────────────────

    /**
     * Donut chart showing failure reason breakdown.
     * Call: {@code updateChart(categories[], counts[])}
     */
    public static String failureCategoryHtml() {
        return """
                <!DOCTYPE html><html><head><meta charset="UTF-8">
                <style>%s</style>
                <script src="%s"></script>
                </head><body><div id="chart"></div>
                <script>
                var chart = echarts.init(document.getElementById('chart'));
                function updateChart(categories, counts) {
                  var data = [];
                  for(var i=0;i<categories.length;i++) data.push({name:categories[i],value:counts[i]});
                  chart.setOption({
                    backgroundColor:'#ffffff',
                    tooltip:{trigger:'item',formatter:'{b}: {c} ({d}%%)'},
                    legend:{orient:'vertical',right:10,top:'center',textStyle:{color:'#6b7280',fontSize:11}},
                    color:['#ef4444','#f97316','#eab308','#6366f1','#8b5cf6','#ec4899','#64748b'],
                    series:[{type:'pie',radius:['40%%','70%%'],center:['40%%','50%%'],
                      label:{show:true,formatter:'{b}\\n{d}%%',color:'#374151',fontSize:11},
                      emphasis:{label:{fontSize:13,fontWeight:'bold'}},
                      data:data,
                      itemStyle:{borderRadius:4,borderColor:'#ffffff',borderWidth:2}}]
                  });
                }
                window.addEventListener('resize',function(){chart.resize();});
                </script></body></html>
                """.formatted(COMMON_STYLE, ECHARTS_CDN);
    }

    // ── Campaign Radar ───────────────────────────────────────────────────────

    /**
     * Radar chart comparing campaigns on Delivery%, Open%, Click%, Success%.
     * Call: {@code updateChart(names[], values[])} where values is array of [delivery,open,click,success].
     */
    public static String campaignRadarHtml() {
        return """
                <!DOCTYPE html><html><head><meta charset="UTF-8">
                <style>%s</style>
                <script src="%s"></script>
                </head><body><div id="chart"></div>
                <script>
                var chart = echarts.init(document.getElementById('chart'));
                var colors = ['#2563eb','#7c3aed','#16a34a','#f97316','#ef4444'];
                function updateChart(names, values) {
                  var series = [];
                  for(var i=0;i<names.length;i++){
                    series.push({value:values[i],name:names[i],
                      lineStyle:{color:colors[i%%colors.length],width:2},
                      itemStyle:{color:colors[i%%colors.length]},
                      areaStyle:{color:colors[i%%colors.length],opacity:0.1}});
                  }
                  chart.setOption({
                    backgroundColor:'#ffffff',
                    tooltip:{},
                    legend:{data:names,bottom:0,textStyle:{color:'#6b7280',fontSize:11}},
                    radar:{indicator:[
                      {name:'Delivery %%',max:100},{name:'Open %%',max:100},
                      {name:'Click %%',max:100},{name:'Success %%',max:100}],
                      shape:'circle',splitNumber:4,
                      axisName:{color:'#6b7280',fontSize:11},
                      splitLine:{lineStyle:{color:'#eef2f7'}},
                      splitArea:{areaStyle:{color:['#ffffff','#f8fafc']}}},
                    series:[{type:'radar',data:series}]
                  });
                }
                window.addEventListener('resize',function(){chart.resize();});
                </script></body></html>
                """.formatted(COMMON_STYLE, ECHARTS_CDN);
    }

    // ── Delivery Funnel ──────────────────────────────────────────────────────

    /**
     * Funnel chart: Recipients -> Delivered -> Opened -> Clicked.
     * Call: {@code updateChart(recipients, delivered, opened, clicked)}
     */
    public static String deliveryFunnelHtml() {
        return """
                <!DOCTYPE html><html><head><meta charset="UTF-8">
                <style>%s</style>
                <script src="%s"></script>
                </head><body><div id="chart"></div>
                <script>
                var chart = echarts.init(document.getElementById('chart'));
                function updateChart(recipients, delivered, opened, clicked) {
                  chart.setOption({
                    backgroundColor:'#ffffff',
                    tooltip:{trigger:'item',formatter:'{b}: {c}'},
                    color:['#2563eb','#3b82f6','#16a34a','#7c3aed'],
                    series:[{type:'funnel',left:'10%%',top:20,bottom:20,width:'80%%',
                      sort:'descending',gap:4,
                      label:{show:true,position:'inside',formatter:'{b}\\n{c}',color:'#ffffff',fontSize:12},
                      itemStyle:{borderColor:'#ffffff',borderWidth:2,borderRadius:4},
                      emphasis:{label:{fontSize:14,fontWeight:'bold'}},
                      data:[
                        {value:recipients,name:'Recipients'},
                        {value:delivered,name:'Delivered'},
                        {value:opened,name:'Opened'},
                        {value:clicked,name:'Clicked'}
                      ]}]
                  });
                }
                window.addEventListener('resize',function(){chart.resize();});
                </script></body></html>
                """.formatted(COMMON_STYLE, ECHARTS_CDN);
    }

    // ── Contact Group Performance (Grouped Bar) ──────────────────────────────

    /**
     * Grouped bar chart comparing contact groups.
     * Call: {@code updateChart(groups[], sent[], failed[], openRates[])}
     */
    public static String contactGroupPerformanceHtml() {
        return """
                <!DOCTYPE html><html><head><meta charset="UTF-8">
                <style>%s</style>
                <script src="%s"></script>
                </head><body><div id="chart"></div>
                <script>
                var chart = echarts.init(document.getElementById('chart'));
                function updateChart(groups, sent, failed, openRates) {
                  chart.setOption({
                    backgroundColor:'#ffffff',
                    tooltip:{trigger:'axis',axisPointer:{type:'shadow'}},
                    legend:{data:['Sent','Failed','Open Rate %%'],textStyle:{color:'#6b7280',fontSize:11},top:4},
                    grid:{left:50,right:50,top:40,bottom:50},
                    xAxis:{type:'category',data:groups,axisLabel:{color:'#6b7280',fontSize:10,rotate:20},
                           axisLine:{lineStyle:{color:'#dde3ec'}},axisTick:{show:false}},
                    yAxis:[
                      {type:'value',name:'Count',axisLabel:{color:'#6b7280',fontSize:11},
                       splitLine:{lineStyle:{color:'#eef2f7'}},axisLine:{show:false}},
                      {type:'value',name:'Rate %%',max:100,axisLabel:{color:'#6b7280',fontSize:11,formatter:'{value}%%'},
                       splitLine:{show:false},axisLine:{show:false}}
                    ],
                    series:[
                      {name:'Sent',type:'bar',data:sent,barMaxWidth:24,
                       itemStyle:{color:'#2563eb',borderRadius:[3,3,0,0]}},
                      {name:'Failed',type:'bar',data:failed,barMaxWidth:24,
                       itemStyle:{color:'#ef4444',borderRadius:[3,3,0,0]}},
                      {name:'Open Rate %%',type:'line',yAxisIndex:1,data:openRates,smooth:true,
                       lineStyle:{color:'#16a34a',width:2},itemStyle:{color:'#16a34a'},
                       symbol:'circle',symbolSize:6}
                    ]
                  });
                }
                window.addEventListener('resize',function(){chart.resize();});
                </script></body></html>
                """.formatted(COMMON_STYLE, ECHARTS_CDN);
    }

    // ── Template Performance (Horizontal Bar) ────────────────────────────────

    /**
     * Horizontal bar chart showing open rate per template.
     * Call: {@code updateChart(templates[], openRates[])}
     */
    public static String templatePerformanceHtml() {
        return """
                <!DOCTYPE html><html><head><meta charset="UTF-8">
                <style>%s</style>
                <script src="%s"></script>
                </head><body><div id="chart"></div>
                <script>
                var chart = echarts.init(document.getElementById('chart'));
                function updateChart(templates, openRates) {
                  chart.setOption({
                    backgroundColor:'#ffffff',
                    tooltip:{trigger:'axis',axisPointer:{type:'shadow'},formatter:'{b}: {c}%%'},
                    grid:{left:140,right:30,top:16,bottom:20},
                    xAxis:{type:'value',max:100,axisLabel:{color:'#6b7280',fontSize:11,formatter:'{value}%%'},
                           splitLine:{lineStyle:{color:'#eef2f7'}},axisLine:{show:false}},
                    yAxis:{type:'category',data:templates,axisLabel:{color:'#374151',fontSize:11},
                           axisLine:{lineStyle:{color:'#dde3ec'}},axisTick:{show:false}},
                    series:[{type:'bar',data:openRates,barMaxWidth:18,
                      itemStyle:{color:{type:'linear',x:0,y:0,x2:1,y2:0,
                        colorStops:[{offset:0,color:'#7c3aed'},{offset:1,color:'#2563eb'}]},
                        borderRadius:[0,4,4,0]},
                      label:{show:true,position:'right',formatter:'{c}%%',color:'#374151',fontSize:11}}]
                  });
                }
                window.addEventListener('resize',function(){chart.resize();});
                </script></body></html>
                """.formatted(COMMON_STYLE, ECHARTS_CDN);
    }

    // ── Queue Throughput (Area Chart) ─────────────────────────────────────────

    /**
     * Area chart showing emails processed per minute (live dashboard).
     * Call: {@code updateChart(times[], sentPerMin[], failedPerMin[])}
     */
    public static String queueThroughputHtml() {
        return """
                <!DOCTYPE html><html><head><meta charset="UTF-8">
                <style>%s</style>
                <script src="%s"></script>
                </head><body><div id="chart"></div>
                <script>
                var chart = echarts.init(document.getElementById('chart'));
                function updateChart(times, sentPerMin, failedPerMin) {
                  chart.setOption({
                    backgroundColor:'#ffffff',
                    tooltip:{trigger:'axis'},
                    legend:{data:['Sent/min','Failed/min'],textStyle:{color:'#6b7280',fontSize:11},top:4},
                    grid:{left:44,right:16,top:36,bottom:24},
                    xAxis:{type:'category',data:times,boundaryGap:false,
                           axisLabel:{color:'#6b7280',fontSize:10},
                           axisLine:{lineStyle:{color:'#dde3ec'}},axisTick:{show:false}},
                    yAxis:{type:'value',axisLabel:{color:'#6b7280',fontSize:11},
                           splitLine:{lineStyle:{color:'#eef2f7'}},axisLine:{show:false}},
                    series:[
                      {name:'Sent/min',type:'line',data:sentPerMin,smooth:true,
                       lineStyle:{color:'#2563eb',width:2},itemStyle:{color:'#2563eb'},symbol:'none',
                       areaStyle:{color:{type:'linear',x:0,y:0,x2:0,y2:1,
                         colorStops:[{offset:0,color:'rgba(37,99,235,0.3)'},{offset:1,color:'rgba(37,99,235,0.02)'}]}}},
                      {name:'Failed/min',type:'line',data:failedPerMin,smooth:true,
                       lineStyle:{color:'#ef4444',width:2},itemStyle:{color:'#ef4444'},symbol:'none',
                       areaStyle:{color:{type:'linear',x:0,y:0,x2:0,y2:1,
                         colorStops:[{offset:0,color:'rgba(239,68,68,0.2)'},{offset:1,color:'rgba(239,68,68,0.02)'}]}}}
                    ]
                  });
                }
                window.addEventListener('resize',function(){chart.resize();});
                </script></body></html>
                """.formatted(COMMON_STYLE, ECHARTS_CDN);
    }
}
