package com.example.taller2_icm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.os.StrictMode
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.taller2_icm.databinding.ActivityMapBinding
import com.google.android.gms.location.*
import com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.bonuspack.routing.RoadManager

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding

    // Ubicación
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var lastLocation: Location? = null
    private val MIN_DISTANCE_CHANGE_METERS = 30f
    private val REQUEST_PERMISSIONS = 200

    // Marcadores y ruta
    private var currentMarker: Marker? = null
    private var targetMarker: Marker? = null
    private lateinit var roadManager: RoadManager
    private var roadOverlay: Polyline? = null
    private lateinit var sensorManager: SensorManager
    private lateinit var lightSensor: Sensor
    private val LUX_THRESHOLD = 1000
    private var darkApplied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Config OSMdroid
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // MapView
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(15.0)

        // Long-press / taps del mapa
        binding.mapView.overlays.add(createOverlayEvents())

        // Ubicación
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.Builder(PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()

        // Sensor de luz
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)!!

        // Bonuspack (permitir red en el hilo principal)
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().permitAll().build()
        )
        roadManager = OSRMRoadManager(this, "ANDROID")

        // Permisos de ubicación
        val needed = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (!needed.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, needed, REQUEST_PERMISSIONS)
        } else {
            startLocationUpdates()
        }

        // Buscar dirección (Enter/Done)
        binding.etAddress.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val addressText = binding.etAddress.text.toString()
                if (addressText.isNotBlank()) geocodeAndAddMarker(addressText)
                true
            } else false
        }
    }

    // Overlay de eventos para longpress
    private fun createOverlayEvents(): MapEventsOverlay {
        return MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
            override fun longPressHelper(p: GeoPoint): Boolean {
                addMarkerByPosition(p)
                return true
            }
        })
    }

    // Permisos
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_LONG).show()
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locResult: LocationResult) {
            val loc = locResult.lastLocation ?: return
            if (lastLocation == null || loc.distanceTo(lastLocation!!) > MIN_DISTANCE_CHANGE_METERS) {
                lastLocation = loc
                val gp = GeoPoint(loc.latitude, loc.longitude)
                runOnUiThread { updateCurrentMarker(gp) }
            }
        }
    }

    private fun updateCurrentMarker(geoPoint: GeoPoint) {
        currentMarker?.let { binding.mapView.overlays.remove(it) }
        currentMarker = Marker(binding.mapView).apply {
            position = geoPoint
            title = "Ubicación actual"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        binding.mapView.overlays.add(currentMarker)
        binding.mapView.controller.animateTo(geoPoint)
        binding.mapView.invalidate()
    }

    // Geocoder
    private fun geocodeAndAddMarker(address: String) {
        try {
            val geocoder = Geocoder(this)
            val list = geocoder.getFromLocationName(address, 1)
            if (!list.isNullOrEmpty()) {
                val addr = list[0]
                val gp = GeoPoint(addr.latitude, addr.longitude)
                setTargetMarker(gp, address)
                focusAndZoom(gp)
                toastDistanceTo(gp)
                drawRouteFromMyLocationTo(gp)
            } else {
                Toast.makeText(this, "Dirección no encontrada", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al geocodificar", Toast.LENGTH_SHORT).show()
        }
    }

    // Marcador con longclick
    private fun addMarkerByPosition(gp: GeoPoint) {
        try {
            val geocoder = Geocoder(this)
            val list = geocoder.getFromLocation(gp.latitude, gp.longitude, 1)
            val title = if (!list.isNullOrEmpty()) list[0].getAddressLine(0) else "Marcador"
            setTargetMarker(gp, title)
            focusAndZoom(gp)
            toastDistanceTo(gp)
            drawRouteFromMyLocationTo(gp)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al invertir geocodificación", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTargetMarker(gp: GeoPoint, title: String) {
        targetMarker?.let { binding.mapView.overlays.remove(it) }
        targetMarker = Marker(binding.mapView).apply {
            position = gp
            this.title = title
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        binding.mapView.overlays.add(targetMarker)
        binding.mapView.invalidate()
    }

    private fun focusAndZoom(gp: GeoPoint) {
        binding.mapView.controller.animateTo(gp)
        binding.mapView.controller.setZoom(18.0)
    }

    private fun toastDistanceTo(gp: GeoPoint) {
        lastLocation?.let { loc ->
            val temp = Location("").apply {
                latitude = gp.latitude; longitude = gp.longitude
            }
            val meters = loc.distanceTo(temp)
            val msg = if (meters < 1000)
                "Distancia: %.0f m".format(meters)
            else
                "Distancia: %.2f km".format(meters / 1000)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } ?: Toast.makeText(this, "Aún sin ubicación actual", Toast.LENGTH_SHORT).show()
    }

    //Ruta pal bonus
    private fun drawRouteFromMyLocationTo(dest: GeoPoint) {
        val loc = lastLocation ?: return
        val start = GeoPoint(loc.latitude, loc.longitude)

        val road: Road = (roadManager as OSRMRoadManager).getRoad(arrayListOf(start, dest))

        roadOverlay?.let { binding.mapView.overlays.remove(it) }
        roadOverlay = RoadManager.buildRoadOverlay(road).apply {
            outlinePaint.color = Color.RED
            outlinePaint.strokeWidth = 10f
        }
        binding.mapView.overlays.add(roadOverlay)
        binding.mapView.invalidate()
    }

    //Sensor de luz
    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val dark = event.values[0] < LUX_THRESHOLD
            applyDarkStyle(dark)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun applyDarkStyle(enable: Boolean) {
        if (enable == darkApplied) return
        binding.mapView.overlayManager.tilesOverlay.setColorFilter(
            if (enable) TilesOverlay.INVERT_COLORS else null
        )
        darkApplied = enable

        //Color de la ruta en oscuro tmbn
        roadOverlay?.let {
            it.outlinePaint.color = if (enable) Color.YELLOW else Color.RED
        }

        binding.mapView.invalidate()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        // Forzado: siempre usar sensor de luz
        sensorManager.registerListener(lightListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(lightListener, lightSensor)
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.mapView.onDetach()
    }

}
