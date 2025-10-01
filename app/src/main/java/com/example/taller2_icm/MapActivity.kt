package com.example.taller2_icm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.taller2_icm.databinding.ActivityMapBinding
import com.google.android.gms.location.*
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var lastLocation: Location? = null
    private val MIN_DISTANCE_CHANGE_METERS = 30f
    private val REQUEST_PERMISSIONS = 200
    private var currentMarker: Marker? = null
    private val jsonFilename = "locations.json"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configuración de osmdroid
        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configurar mapa
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(15.0)

        // Inicializar ubicación
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.create().apply {
            interval = 5000
            fastestInterval = 2000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        // Pedir permisos
        val needed = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (!needed.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, needed, REQUEST_PERMISSIONS)
        } else {
            startLocationUpdates()
        }

        // Buscar dirección al presionar "Done"
        binding.etAddress.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val addressText = binding.etAddress.text.toString()
                if (addressText.isNotBlank()) {
                    geocodeAndAddMarker(addressText)
                }
                true
            } else {
                false
            }
        }

        // Long click para añadir marcador por posición
        binding.mapView.setOnLongClickListener { motionEvent ->
            val geoPoint = binding.mapView.projection.fromPixels(
                motionEvent.x.toInt(),
                motionEvent.y.toInt()
            ) as GeoPoint
            addMarkerByPosition(geoPoint)
            true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startLocationUpdates()
            } else {
                Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locResult: LocationResult) {
            super.onLocationResult(locResult)
            val loc = locResult.lastLocation ?: return

            if (lastLocation == null || loc.distanceTo(lastLocation!!) > MIN_DISTANCE_CHANGE_METERS) {
                lastLocation = loc
                val gp = GeoPoint(loc.latitude, loc.longitude)
                runOnUiThread {
                    updateCurrentMarker(gp)
                }
                saveLocationToJson(loc)
            }
        }
    }

    private fun updateCurrentMarker(geoPoint: GeoPoint) {
        currentMarker?.let { binding.mapView.overlays.remove(it) }

        val marker = Marker(binding.mapView)
        marker.position = geoPoint
        marker.title = "Ubicación actual"
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        binding.mapView.overlays.add(marker)
        currentMarker = marker

        binding.mapView.controller.animateTo(geoPoint)
        binding.mapView.invalidate()
    }

    private fun saveLocationToJson(loc: Location) {
        try {
            val arr = try {
                val text = openFileInput(jsonFilename).bufferedReader().use { it.readText() }
                JSONArray(text)
            } catch (e: Exception) {
                JSONArray()
            }

            val obj = JSONObject().apply {
                put("lat", loc.latitude)
                put("lon", loc.longitude)
                put("timestamp", System.currentTimeMillis())
            }
            arr.put(obj)

            openFileOutput(jsonFilename, Context.MODE_PRIVATE).use { fos ->
                fos.write(arr.toString().toByteArray())
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun geocodeAndAddMarker(address: String) {
        try {
            val geocoder = Geocoder(this)
            val list = geocoder.getFromLocationName(address, 1)
            if (!list.isNullOrEmpty()) {
                val addr = list[0]
                val gp = GeoPoint(addr.latitude, addr.longitude)
                val marker = Marker(binding.mapView)
                marker.position = gp
                marker.title = address
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                binding.mapView.overlays.add(marker)

                binding.mapView.controller.animateTo(gp)
                binding.mapView.controller.setZoom(18.0)

                lastLocation?.let { loc ->
                    val temp = Location("").apply {
                        latitude = gp.latitude
                        longitude = gp.longitude
                    }
                    val dist = loc.distanceTo(temp)
                    Toast.makeText(this, "Distancia: ${"%.1f".format(dist)} m", Toast.LENGTH_SHORT).show()
                }

                binding.mapView.invalidate()
            } else {
                Toast.makeText(this, "Dirección no encontrada", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addMarkerByPosition(gp: GeoPoint) {
        try {
            val geocoder = Geocoder(this)
            val list = geocoder.getFromLocation(gp.latitude, gp.longitude, 1)
            val title = if (!list.isNullOrEmpty()) list[0].getAddressLine(0) else "Marcador"
            val marker = Marker(binding.mapView)
            marker.position = gp
            marker.title = title
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            binding.mapView.overlays.add(marker)

            binding.mapView.controller.animateTo(gp)
            binding.mapView.controller.setZoom(18.0)

            lastLocation?.let { loc ->
                val temp = Location("").apply {
                    latitude = gp.latitude
                    longitude = gp.longitude
                }
                val dist = loc.distanceTo(temp)
                Toast.makeText(this, "Distancia: ${"%.1f".format(dist)} m", Toast.LENGTH_SHORT).show()
            }

            binding.mapView.invalidate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.mapView.onDetach()
    }
}
