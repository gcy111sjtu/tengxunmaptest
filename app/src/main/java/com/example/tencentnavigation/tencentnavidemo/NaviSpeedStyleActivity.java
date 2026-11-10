package com.example.tencentnavigation.tencentnavidemo;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.constraint.ConstraintLayout;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.tencent.map.navi.INaviView;
import com.tencent.map.navi.data.GuidedLaneInfo;
import com.tencent.map.navi.data.NavigationData;
import com.tencent.map.navi.data.RouteData;
import com.tencent.map.navi.data.RouteTrafficStatus;
import com.tencent.map.navi.data.TrafficItem;
import com.tencent.tencentmap.mapsdk.maps.model.LatLng;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过模拟导航逐段采集 NavigationData 中的当前道路名和限速，并支持导出 CSV。
 * RouteData 本身不包含整条路线每个道路段的限速值。
 */
public class NaviSpeedStyleActivity extends BaseActivity {
    private static final int REQUEST_EXPORT_CSV = 1001;

    private ConstraintLayout speedBar;
    private TextView roadName;
    private TextView limitSpeed;
    private TextView speed;
    private TextView recordsView;
    private final List<SpeedLimitRecord> records = new ArrayList<>();
    private String lastRoadName;
    private int lastLimitSpeed = Integer.MIN_VALUE;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initView();
    }

    private void initView() {
        speedBar = findViewById(R.id.navi_speed_style);
        roadName = findViewById(R.id.navi_road_name);
        limitSpeed = findViewById(R.id.navi_limit_speed);
        speed = findViewById(R.id.navi_speed);
        recordsView = findViewById(R.id.speed_limit_records);
        Button exportButton = findViewById(R.id.export_speed_limits);
        Button clearButton = findViewById(R.id.clear_speed_limits);

        speedBar.setVisibility(View.VISIBLE);
        exportButton.setOnClickListener(v -> exportCsv());
        clearButton.setOnClickListener(v -> clearRecords());

        if (tencentCarNaviManager != null) {
            tencentCarNaviManager.addNaviView(speedView);
        }
    }

    private void collectSpeedLimit(NavigationData data) {
        final String currentRoad = data.getCurrentRoadName() == null
                ? "" : data.getCurrentRoadName().trim();
        final int currentLimit = data.getLimitSpeed();
        final int currentSpeed = data.getCurrentSpeed();

        runOnUiThread(() -> {
            roadName.setText(TextUtils.isEmpty(currentRoad) ? "未知道路" : currentRoad);
            speed.setText(String.valueOf(currentSpeed));
            limitSpeed.setText(currentLimit > 0 ? String.valueOf(currentLimit) : "--");

            // 无道路名的数据无法形成有意义的线路记录；相邻相同数据只保留一次。
            if (TextUtils.isEmpty(currentRoad)
                    || currentRoad.equals(lastRoadName) && currentLimit == lastLimitSpeed) {
                return;
            }
            records.add(new SpeedLimitRecord(records.size() + 1, currentRoad, currentLimit));
            lastRoadName = currentRoad;
            lastLimitSpeed = currentLimit;
            refreshRecords();
        });
    }

    private void clearRecords() {
        records.clear();
        lastRoadName = null;
        lastLimitSpeed = Integer.MIN_VALUE;
        refreshRecords();
    }

    private void refreshRecords() {
        if (records.isEmpty()) {
            recordsView.setText(R.string.speed_limit_empty);
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (SpeedLimitRecord record : records) {
            builder.append(record.sequence)
                    .append(". ")
                    .append(record.roadName)
                    .append("  |  限速 ")
                    .append(record.limitSpeed > 0 ? record.limitSpeed + " km/h" : "未知/未提供")
                    .append('\n');
        }
        recordsView.setText(builder.toString());
    }

    private void exportCsv() {
        if (records.isEmpty()) {
            Toast.makeText(this, "当前没有可导出的线路限速数据", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "route_speed_limits.csv");
        startActivityForResult(intent, REQUEST_EXPORT_CSV);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_CSV || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        writeCsv(data.getData());
    }

    private void writeCsv(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri);
             OutputStreamWriter writer = new OutputStreamWriter(output, "UTF-8")) {
            // BOM 让常见中文版 Excel 正确识别 UTF-8 道路名。
            writer.write('\ufeff');
            writer.write("序号,线路名,限速值(km/h)\r\n");
            for (SpeedLimitRecord record : records) {
                writer.write(String.valueOf(record.sequence));
                writer.write(',');
                writer.write(csvEscape(record.roadName));
                writer.write(',');
                writer.write(record.limitSpeed > 0 ? String.valueOf(record.limitSpeed) : "未知/未提供");
                writer.write("\r\n");
            }
            writer.flush();
            Toast.makeText(this, "线路限速 CSV 已导出", Toast.LENGTH_SHORT).show();
        } catch (IOException | NullPointerException e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String csvEscape(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    @Override
    protected void onDestroy() {
        if (tencentCarNaviManager != null) {
            tencentCarNaviManager.removeNaviView(speedView);
        }
        super.onDestroy();
    }

    private final INaviView speedView = new INaviView() {
        @Override
        public void onShowGuidedLaneInfo(GuidedLaneInfo guidedLaneInfo) { }

        @Override
        public void onRouteDidChange(RouteData routeData, ArrayList<TrafficItem> arrayList) { }

        @Override
        public void onDeleteBackupRoutes(ArrayList<String> arrayList) { }

        @Override
        public void onAddBackupRoutes(ArrayList<RouteData> arrayList) { }

        @Override
        public void onUpdateTraffic(RouteTrafficStatus routeTrafficStatus) { }

        @Override
        public void onUpdateBackupRoutesTraffic(ArrayList<RouteTrafficStatus> arrayList) { }

        @Override
        public void onGpsRssiChanged(int i) { }

        @Override
        public void onUpdateNavigationData(NavigationData navigationData) {
            collectSpeedLimit(navigationData);
        }

        @Override
        public void onShowEnlargedIntersection(Bitmap bitmap) { }

        @Override
        public void onHideEnlargedIntersection() { }

        @Override
        public void onShowGuidedLane(Bitmap bitmap) { }

        @Override
        public void onHideGuidedLane() { }

        @Override
        public void onUpdateTraffic(String s, int i, int i1, ArrayList<LatLng> arrayList,
                                    ArrayList<TrafficItem> arrayList1, boolean b) { }

        @Override
        public void onSmartLocStart() { }

        @Override
        public void onSmartLocEnd() { }

        @Override
        public void onGpsWeakNotify() { }

        @Override
        public void onGpsStrongNotify() { }
    };

    private static class SpeedLimitRecord {
        final int sequence;
        final String roadName;
        final int limitSpeed;

        SpeedLimitRecord(int sequence, String roadName, int limitSpeed) {
            this.sequence = sequence;
            this.roadName = roadName;
            this.limitSpeed = limitSpeed;
        }
    }
}
