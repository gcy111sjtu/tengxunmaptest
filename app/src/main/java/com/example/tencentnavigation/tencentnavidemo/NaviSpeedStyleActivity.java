package com.example.tencentnavigation.tencentnavidemo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.constraint.ConstraintLayout;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.tencentnavigation.tencentnavidemo.location.TnkLocationAdapter;
import com.tencent.map.fusionlocation.model.TencentGeoLocation;
import com.tencent.map.geolocation.TencentLocation;
import com.tencent.map.navi.INaviView;
import com.tencent.map.navi.TencentRouteSearchCallback;
import com.tencent.map.navi.car.CarNaviView;
import com.tencent.map.navi.car.CarRouteSearchOptions;
import com.tencent.map.navi.car.TencentCarNaviManager;
import com.tencent.map.navi.data.GpsLocation;
import com.tencent.map.navi.data.GuidedLaneInfo;
import com.tencent.map.navi.data.NaviPoi;
import com.tencent.map.navi.data.NavigationData;
import com.tencent.map.navi.data.RouteData;
import com.tencent.map.navi.data.RouteTrafficStatus;
import com.tencent.map.navi.data.TrafficItem;
import com.tencent.tencentmap.mapsdk.maps.model.LatLng;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 真实导航过程中逐段采集当前道路名和限速，并支持连同位置、时间导出 CSV。
 */
public class NaviSpeedStyleActivity extends AppCompatActivity
        implements TnkLocationAdapter.IGeoLocationListeners {
    private static final int REQUEST_EXPORT_CSV = 1001;
    private static final int REQUEST_DESTINATION = 1002;
    private static final int REQUEST_LOCATION_PERMISSION = 1003;

    private TencentCarNaviManager naviManager;
    private CarNaviView carNaviView;
    private ConstraintLayout speedBar;
    private TextView roadName;
    private TextView limitSpeed;
    private TextView speed;
    private TextView recordsView;
    private TextView navigationStatus;
    private final List<SpeedLimitRecord> records = new ArrayList<>();
    private String lastRoadName;
    private int lastLimitSpeed = Integer.MIN_VALUE;
    private volatile TencentLocation latestLocation;
    private NaviPoi destination;
    private String destinationName;
    private boolean routeRequested;
    private volatile boolean navigating;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_navi);
        initNavigation();
        initView();
        ensureLocationPermission();
    }

    private void initNavigation() {
        naviManager = new TencentCarNaviManager(this);
        carNaviView = findViewById(R.id.carnaviview);
        naviManager.addNaviView(carNaviView);
        naviManager.addNaviView(speedView);
        naviManager.setInternalTtsEnabled(true);
    }

    private void initView() {
        speedBar = findViewById(R.id.navi_speed_style);
        roadName = findViewById(R.id.navi_road_name);
        limitSpeed = findViewById(R.id.navi_limit_speed);
        speed = findViewById(R.id.navi_speed);
        recordsView = findViewById(R.id.speed_limit_records);
        navigationStatus = findViewById(R.id.real_navigation_status);
        Button destinationButton = findViewById(R.id.select_destination);
        Button exportButton = findViewById(R.id.export_speed_limits);
        Button clearButton = findViewById(R.id.clear_speed_limits);

        speedBar.setVisibility(View.VISIBLE);
        destinationButton.setOnClickListener(v -> selectDestination());
        exportButton.setOnClickListener(v -> exportCsv());
        clearButton.setOnClickListener(v -> clearRecords());
    }

    private void ensureLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
            return;
        }
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, REQUEST_LOCATION_PERMISSION);
    }

    private void startLocationUpdates() {
        TnkLocationAdapter adapter = TnkLocationAdapter.mTnkLocationSingleton.get();
        adapter.setContext(getApplicationContext());
        adapter.startTNKLocationAdapter();
        adapter.addGeoLocationListener(this);
        navigationStatus.setText(R.string.real_navigation_waiting_location);
    }

    private void selectDestination() {
        startActivityForResult(new Intent(this, LocationSearchActivity.class), REQUEST_DESTINATION);
    }

    private void calculateRealRoute() {
        TencentLocation location = latestLocation;
        if (location == null || destination == null || routeRequested || naviManager == null) {
            return;
        }
        routeRequested = true;
        NaviPoi start = new NaviPoi(location.getLatitude(), location.getLongitude());
        CarRouteSearchOptions options = CarRouteSearchOptions.create();
        navigationStatus.setText(getString(R.string.real_navigation_calculating, destinationName));
        try {
            naviManager.searchRoute(start, destination, null, options, routeSearchCallback);
        } catch (Exception e) {
            navigationStatus.setText(getString(R.string.real_navigation_route_failed,
                    messageOf(e, "未知错误")));
        }
    }

    @Override
    public void onGeoLocationChanged(TencentGeoLocation geoLocation) {
        if (geoLocation == null || geoLocation.getLocation() == null) {
            return;
        }
        latestLocation = geoLocation.getLocation();
        TencentCarNaviManager manager = naviManager;
        if (manager != null) {
            manager.updateLocation(toGpsLocation(latestLocation), geoLocation.getStatus(),
                    geoLocation.getReason());
        }
        runOnUiThread(() -> {
            if (!navigating && !routeRequested) {
                navigationStatus.setText(destination == null
                        ? R.string.real_navigation_select_destination
                        : R.string.real_navigation_location_ready);
            }
            calculateRealRoute();
        });
    }

    private GpsLocation toGpsLocation(TencentLocation source) {
        GpsLocation location = new GpsLocation();
        location.setDirection(source.getBearing());
        location.setAccuracy(source.getAccuracy());
        location.setLatitude(source.getLatitude());
        location.setLongitude(source.getLongitude());
        location.setAltitude(source.getAltitude());
        location.setProvider(source.getProvider());
        location.setVelocity(source.getSpeed());
        location.setTime(source.getTime());
        return location;
    }

    private final TencentRouteSearchCallback routeSearchCallback = new TencentRouteSearchCallback() {
        @Override
        public void onRouteSearchFailure(int errorCode, String errorMessage) {
            runOnUiThread(() -> navigationStatus.setText(getString(
                    R.string.real_navigation_route_failed, errorCode + ": " + errorMessage)));
        }

        @Override
        public void onRouteSearchSuccess(ArrayList<RouteData> routes) {
            if (routes == null || routes.isEmpty() || naviManager == null) {
                onRouteSearchFailure(-1, "未返回可用路线");
                return;
            }
            try {
                naviManager.startNavi(0);
                navigating = true;
                runOnUiThread(() -> navigationStatus.setText(R.string.real_navigation_started));
            } catch (Exception e) {
                onRouteSearchFailure(-1, messageOf(e, "启动导航失败"));
            }
        }
    };

    private String messageOf(Exception e, String fallback) {
        return TextUtils.isEmpty(e.getMessage()) ? fallback : e.getMessage();
    }

    private void collectSpeedLimit(NavigationData data) {
        final String currentRoad = data.getCurrentRoadName() == null
                ? "" : data.getCurrentRoadName().trim();
        final int currentLimit = data.getLimitSpeed();
        final int currentSpeed = data.getCurrentSpeed();
        final TencentLocation location = latestLocation;
        final double latitude = location == null ? Double.NaN : location.getLatitude();
        final double longitude = location == null ? Double.NaN : location.getLongitude();
        final long collectedAt = System.currentTimeMillis();

        runOnUiThread(() -> {
            roadName.setText(TextUtils.isEmpty(currentRoad) ? "未知道路" : currentRoad);
            speed.setText(String.valueOf(currentSpeed));
            limitSpeed.setText(currentLimit > 0 ? String.valueOf(currentLimit) : "--");
            if (TextUtils.isEmpty(currentRoad)
                    || currentRoad.equals(lastRoadName) && currentLimit == lastLimitSpeed) {
                return;
            }
            records.add(new SpeedLimitRecord(records.size() + 1, currentRoad, currentLimit,
                    latitude, longitude, collectedAt));
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
            recordsView.setText(R.string.speed_limit_empty_real);
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (SpeedLimitRecord record : records) {
            builder.append(record.sequence).append(". ").append(record.roadName)
                    .append("  |  限速 ")
                    .append(record.limitSpeed > 0 ? record.limitSpeed + " km/h" : "未知/未提供")
                    .append('\n');
        }
        recordsView.setText(builder.toString());
    }

    private void exportCsv() {
        if (records.isEmpty()) {
            Toast.makeText(this, "当前没有可导出的真实道路限速数据", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "real_route_speed_limits.csv");
        startActivityForResult(intent, REQUEST_EXPORT_CSV);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DESTINATION && resultCode == Activity.RESULT_OK && data != null) {
            if (navigating && naviManager != null) {
                naviManager.stopNavi();
                navigating = false;
            }
            destinationName = data.getStringExtra("address");
            destination = new NaviPoi(data.getFloatExtra("latitude", 0),
                    data.getFloatExtra("longitude", 0));
            routeRequested = false;
            navigationStatus.setText(latestLocation == null
                    ? R.string.real_navigation_waiting_location
                    : R.string.real_navigation_location_ready);
            calculateRealRoute();
            return;
        }
        if (requestCode == REQUEST_EXPORT_CSV && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            writeCsv(data.getData());
        }
    }

    private void writeCsv(Uri uri) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        try (OutputStream output = getContentResolver().openOutputStream(uri);
             OutputStreamWriter writer = new OutputStreamWriter(output, "UTF-8")) {
            writer.write('\ufeff');
            writer.write("序号,道路名,限速值(km/h),纬度,经度,采集时间\r\n");
            for (SpeedLimitRecord record : records) {
                writer.write(String.valueOf(record.sequence));
                writer.write(',');
                writer.write(csvEscape(record.roadName));
                writer.write(',');
                writer.write(record.limitSpeed > 0 ? String.valueOf(record.limitSpeed) : "未知/未提供");
                writer.write(',');
                writer.write(Double.isNaN(record.latitude) ? "" : String.valueOf(record.latitude));
                writer.write(',');
                writer.write(Double.isNaN(record.longitude) ? "" : String.valueOf(record.longitude));
                writer.write(',');
                writer.write(csvEscape(formatter.format(new Date(record.collectedAt))));
                writer.write("\r\n");
            }
            writer.flush();
            Toast.makeText(this, "真实道路限速 CSV 已导出", Toast.LENGTH_SHORT).show();
        } catch (IOException | NullPointerException e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String csvEscape(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        } else if (requestCode == REQUEST_LOCATION_PERMISSION) {
            navigationStatus.setText(R.string.real_navigation_permission_required);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (carNaviView != null) carNaviView.onStart();
    }

    @Override protected void onRestart() {
        super.onRestart();
        if (carNaviView != null) carNaviView.onRestart();
    }

    @Override protected void onResume() {
        super.onResume();
        if (carNaviView != null) carNaviView.onResume();
    }

    @Override protected void onPause() {
        if (carNaviView != null) carNaviView.onPause();
        super.onPause();
    }

    @Override protected void onStop() {
        if (carNaviView != null) carNaviView.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        TnkLocationAdapter.mTnkLocationSingleton.get().removeGeoLocationListener(this);
        if (naviManager != null) {
            if (naviManager.isNavigating()) naviManager.stopNavi();
            naviManager.removeNaviView(speedView);
        }
        if (carNaviView != null) carNaviView.onDestroy();
        super.onDestroy();
    }

    private final INaviView speedView = new INaviView() {
        @Override public void onShowGuidedLaneInfo(GuidedLaneInfo info) { }
        @Override public void onRouteDidChange(RouteData route, ArrayList<TrafficItem> items) { }
        @Override public void onDeleteBackupRoutes(ArrayList<String> routes) { }
        @Override public void onAddBackupRoutes(ArrayList<RouteData> routes) { }
        @Override public void onUpdateTraffic(RouteTrafficStatus status) { }
        @Override public void onUpdateBackupRoutesTraffic(ArrayList<RouteTrafficStatus> statuses) { }
        @Override public void onGpsRssiChanged(int rssi) { }
        @Override public void onUpdateNavigationData(NavigationData data) { collectSpeedLimit(data); }
        @Override public void onShowEnlargedIntersection(Bitmap bitmap) { }
        @Override public void onHideEnlargedIntersection() { }
        @Override public void onShowGuidedLane(Bitmap bitmap) { }
        @Override public void onHideGuidedLane() { }
        @Override public void onUpdateTraffic(String routeId, int start, int end,
                ArrayList<LatLng> points, ArrayList<TrafficItem> items, boolean finished) { }
        @Override public void onSmartLocStart() { }
        @Override public void onSmartLocEnd() { }
        @Override public void onGpsWeakNotify() { }
        @Override public void onGpsStrongNotify() { }
    };

    private static class SpeedLimitRecord {
        final int sequence;
        final String roadName;
        final int limitSpeed;
        final double latitude;
        final double longitude;
        final long collectedAt;

        SpeedLimitRecord(int sequence, String roadName, int limitSpeed, double latitude,
                         double longitude, long collectedAt) {
            this.sequence = sequence;
            this.roadName = roadName;
            this.limitSpeed = limitSpeed;
            this.latitude = latitude;
            this.longitude = longitude;
            this.collectedAt = collectedAt;
        }
    }
}
