package com.repository.navigation.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.repository.navigation.NavigationManager
import com.repository.navigation.NavigationStateListener
import com.repository.navigation.R
import com.repository.navigation.db.NavigationDatabase
import com.repository.navigation.db.RecentDestinationDao
import com.repository.navigation.db.RecentDestinationEntity
import com.repository.navigation.model.RouteAlternative
import com.repository.navigation.model.RouteInfo
import com.repository.navigation.model.RoutePoint
import com.repository.navigation.model.RoutePointType
import com.repository.navigation.model.TransitSection
import com.repository.navigation.model.TransitSectionType
import com.repository.navigation.model.TransportMethodInfo
import com.repository.navigation.model.TransportMode
import com.repository.navigation.provider.InteractiveMap
import com.repository.navigation.provider.MapProviders
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.yandex.mapkit.geometry.Point
import kotlinx.coroutines.launch
import kotlin.math.round

class NavigationFragment : Fragment(), NavigationStateListener {

    companion object {
        private const val TAG = "NavigationFragment"
        // Active-journey camera follows the user showing ~this many meters from
        // center to screen edge (a ~300m navigation view), instead of framing the
        // whole route.
        private const val FOLLOW_RADIUS_METERS = 100.0
    }

    private enum class SheetState {
        IDLE, SEARCH, DESTINATION_PREVIEW, PLANNING, ROUTE_SELECTION, ROUTE_DETAILS, ACTIVE
    }

    // The neutral container the active provider's map view is attached into.
    private var mapContainer: FrameLayout? = null
    private var interactiveMap: InteractiveMap? = null
    // The provider id the current interactiveMap belongs to. If the user switches
    // map providers while idle, onResume rebuilds the map from the new provider.
    private var interactiveMapProviderId: String? = null
    private var savedMapState: Bundle? = null

    private var bottomSheet: FrameLayout? = null
    private var mapControls: LinearLayout? = null
    private var btnZoomIn: ImageButton? = null
    private var btnZoomOut: ImageButton? = null
    private var btnMyLocation: ImageButton? = null

    private var currentSheetOffsetPx: Int = 0

    // IDLE
    private var idleView: LinearLayout? = null
    private var idleSearchPill: TextInputEditText? = null
    private var recentRecycler: RecyclerView? = null

    // SEARCH
    private var searchView: LinearLayout? = null
    private var searchBackButton: ImageButton? = null
    private var searchEditText: TextInputEditText? = null
    private var searchResultsRecycler: RecyclerView? = null

    // DESTINATION_PREVIEW
    private var destPreviewView: LinearLayout? = null
    private var destName: TextView? = null
    private var destCityPostal: TextView? = null
    private var destCoords: TextView? = null
    private var destCopyButton: ImageButton? = null
    private var destApproxEtaRow: LinearLayout? = null
    private var destApproxEtaText: TextView? = null
    private var destApproxDistText: TextView? = null
    private var destRouteButton: MaterialButton? = null

    // PLANNING
    private var planningView: LinearLayout? = null

    // ROUTE_SELECTION
    private var routeSelectionView: LinearLayout? = null
    private var routeFromText: TextView? = null
    private var routeToText: TextView? = null
    private var refreshRoutesButton: ImageButton? = null
    private var routeMenuButton: ImageButton? = null
    private var routeMenuBadge: View? = null
    private var modeChipGroup: ChipGroup? = null
    private var routeVariationsRecycler: RecyclerView? = null
    private var routeDetailsButton: MaterialButton? = null
    private var routeStartButton: MaterialButton? = null

    // ROUTE_DETAILS
    private var routeDetailsView: LinearLayout? = null
    private var detailsModeIcon: ImageView? = null
    private var detailsEtaText: TextView? = null
    private var detailsDistanceText: TextView? = null
    private var detailsStepsRecycler: RecyclerView? = null
    private var detailsBackButton: ImageView? = null
    private var detailsStartButton: MaterialButton? = null

    // ACTIVE
    private var activeView: LinearLayout? = null
    private var activeEtaText: TextView? = null
    private var activeInstructionText: TextView? = null
    private var activeStepsRecycler: RecyclerView? = null
    private var activeDetailsButton: MaterialButton? = null
    private var stopButton: MaterialButton? = null

    private var lastCameraFitPoints: List<Point>? = null

    // Compass heading for user location arrow
    private var sensorManager: android.hardware.SensorManager? = null
    private var compassHeading = 0f

    private val compassListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            if (event.sensor.type == android.hardware.Sensor.TYPE_ROTATION_VECTOR) {
                val rotationMatrix = FloatArray(9)
                android.hardware.SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                android.hardware.SensorManager.getOrientation(rotationMatrix, orientation)
                var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (azimuth < 0) azimuth += 360f
                compassHeading = azimuth
                interactiveMap?.updateHeading(azimuth)
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor, accuracy: Int) {}
    }

    // State
    private lateinit var navigationManager: NavigationManager
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>
    private var currentSheetState: SheetState = SheetState.IDLE
    private var stepsVisible = false

    // Adapters
    private val recentAdapter = SuggestAdapter { item -> onSuggestItemChosen(item) }
    private val searchAdapter = SuggestAdapter { item -> onSuggestItemChosen(item) }
    private val alternativeAdapter = RouteAlternativeAdapter { alt -> onAlternativeSelected(alt) }
    private val detailsTransitAdapter = TransitTimelineAdapter()
    private val detailsInstructionAdapter = NavigationInstructionAdapter()
    private val activeTransitAdapter = TransitSectionAdapter()
    private val activeInstructionAdapter = NavigationInstructionAdapter()

    private var currentUserLocation: Point? = null
    private var destinationPoint: Point? = null
    private var destinationLabel: String = ""

    // Recent destinations
    private var recentDao: RecentDestinationDao? = null

    // Current route selection state
    private var cachedMethods: List<TransportMethodInfo> = emptyList()
    private var currentSelectedMode: TransportMode = TransportMode.TRANSIT
    private var currentAlternatives: List<RouteAlternative> = emptyList()
    private var selectedDepartureTime: Date? = null
    private var currentWaypoints: List<Point> = emptyList()

    // Route editing state
    private var editingRoutePoints: MutableList<RoutePoint>? = null
    private var editingPointIndex: Int = -1
    private var changeRouteDialog: BottomSheetDialog? = null

    // Planning timeout
    private val planningHandler = Handler(Looper.getMainLooper())
    private val planningTimeout = Runnable {
        if (!isAdded) return@Runnable
        if (currentSheetState == SheetState.PLANNING) {
            Log.w(TAG, "Route planning timed out")
            Toast.makeText(context, "Route search timed out", Toast.LENGTH_SHORT).show()
            showSheetState(SheetState.IDLE)
        }
    }

    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_navigation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)

        // NavigationManager must be resolved before setupMap so the InteractiveMap
        // is built over the SAME LocationEngine the journey uses (N10).
        navigationManager = NavigationManager.getInstance(requireContext())
        navigationManager.stateListener = this
        recentDao = NavigationDatabase.getInstance(requireContext()).recentDestinationDao()

        setupMap(savedInstanceState)
        setupSearch()
        setupBottomSheet()
        setupAdapters()
        setupMapControls()
        setupDestPreview()
        setupRouteSelection()
        setupRouteDetails()
        setupActive()
        setupBackHandling()

        MapProviders.active.routing.warmUp()
        fetchUserLocation()
        loadRecents("")
        onStateChanged(navigationManager.getState())
    }

    private fun bindViews(view: View) {
        mapContainer = view.findViewById(R.id.mapContainer)
        bottomSheet = view.findViewById(R.id.bottomSheet)
        mapControls = view.findViewById(R.id.mapControls)
        btnZoomIn = view.findViewById(R.id.btnZoomIn)
        btnZoomOut = view.findViewById(R.id.btnZoomOut)
        btnMyLocation = view.findViewById(R.id.btnMyLocation)

        idleView = view.findViewById(R.id.idleView)
        idleSearchPill = view.findViewById(R.id.idleSearchPill)
        recentRecycler = view.findViewById(R.id.recentRecycler)

        searchView = view.findViewById(R.id.searchView)
        searchBackButton = view.findViewById(R.id.searchBackButton)
        searchEditText = view.findViewById(R.id.searchEditText)
        searchResultsRecycler = view.findViewById(R.id.searchResultsRecycler)

        destPreviewView = view.findViewById(R.id.destPreviewView)
        destName = view.findViewById(R.id.destName)
        destCityPostal = view.findViewById(R.id.destCityPostal)
        destCoords = view.findViewById(R.id.destCoords)
        destCopyButton = view.findViewById(R.id.destCopyButton)
        destApproxEtaRow = view.findViewById(R.id.destApproxEtaRow)
        destApproxEtaText = view.findViewById(R.id.destApproxEtaText)
        destApproxDistText = view.findViewById(R.id.destApproxDistText)
        destRouteButton = view.findViewById(R.id.destRouteButton)

        planningView = view.findViewById(R.id.planningView)

        routeSelectionView = view.findViewById(R.id.routeSelectionView)
        routeFromText = view.findViewById(R.id.routeFromText)
        routeToText = view.findViewById(R.id.routeToText)
        refreshRoutesButton = view.findViewById(R.id.refreshRoutesButton)
        routeMenuButton = view.findViewById(R.id.routeMenuButton)
        routeMenuBadge = view.findViewById(R.id.routeMenuBadge)
        modeChipGroup = view.findViewById(R.id.modeChipGroup)
        routeVariationsRecycler = view.findViewById(R.id.routeVariationsRecycler)
        routeDetailsButton = view.findViewById(R.id.routeDetailsButton)
        routeStartButton = view.findViewById(R.id.routeStartButton)

        routeDetailsView = view.findViewById(R.id.routeDetailsView)
        detailsModeIcon = view.findViewById(R.id.detailsModeIcon)
        detailsEtaText = view.findViewById(R.id.detailsEtaText)
        detailsDistanceText = view.findViewById(R.id.detailsDistanceText)
        detailsStepsRecycler = view.findViewById(R.id.detailsStepsRecycler)
        detailsBackButton = view.findViewById(R.id.detailsBackButton)
        detailsStartButton = view.findViewById(R.id.detailsStartButton)

        activeView = view.findViewById(R.id.activeView)
        activeEtaText = view.findViewById(R.id.activeEtaText)
        activeInstructionText = view.findViewById(R.id.activeInstructionText)
        activeStepsRecycler = view.findViewById(R.id.activeStepsRecycler)
        activeDetailsButton = view.findViewById(R.id.activeDetailsButton)
        stopButton = view.findViewById(R.id.stopButton)
    }

    private fun setupMap(savedInstanceState: Bundle?) {
        savedMapState = savedInstanceState
        buildInteractiveMap(savedInstanceState)

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        rotationSensor?.let {
            sensorManager?.registerListener(compassListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
    }

    /**
     * Build the active provider's InteractiveMap into [mapContainer] and record the
     * provider it belongs to. Tears down any previous map first so a provider switch
     * leaves no orphaned map view. Shares the journey's LocationEngine (N10).
     */
    private fun buildInteractiveMap(savedInstanceState: Bundle?) {
        val container = mapContainer ?: return
        interactiveMap?.let {
            it.onDestroy()
            container.removeAllViews()
        }
        val map = MapProviders.active.createInteractiveMap(
            requireContext(), navigationManager.getLocationEngine()
        )
        // attach() must run before onCreate(): the Google MapView is constructed
        // inside attach(), and onCreate() forwards to that MapView. Calling
        // onCreate() first would no-op against a null MapView, so Google Maps would
        // never build its GL surface and onMapReady would never fire (blank map).
        map.attach(container)
        map.onCreate(savedInstanceState)
        map.setMyLocationEnabled(true)
        map.setOnMapTap {
            if (currentSheetState == SheetState.SEARCH) showSheetState(SheetState.IDLE)
        }
        map.setOnMapLongTap { point ->
            if (isAdded) setDestination(point, "Dropped pin")
        }
        interactiveMap = map
        interactiveMapProviderId = MapProviders.active.id
    }

    /**
     * If the active map provider changed since this fragment built its map (a
     * runtime idle-only switch), rebuild the InteractiveMap from the new provider.
     * Called from onResume so the next time the user opens the nav tab the map uses
     * the new provider. Limitation: a switch while the nav tab is foreground only
     * takes visual effect after the fragment is resumed again (the switch itself is
     * gated to IDLE, so no active journey is disrupted).
     */
    private fun rebuildMapIfProviderChanged() {
        val active = MapProviders.active.id
        if (interactiveMapProviderId != null && interactiveMapProviderId != active) {
            Log.i(TAG, "Map provider changed to $active; rebuilding InteractiveMap")
            buildInteractiveMap(savedMapState)
            interactiveMap?.onStart()
            currentUserLocation?.let { moveCameraTo(it, 14f) } ?: fetchUserLocation()
        }
    }

    private fun setupSearch() {
        idleSearchPill?.setOnClickListener { showSheetState(SheetState.SEARCH) }
        searchBackButton?.setOnClickListener { showSheetState(SheetState.IDLE) }
        searchEditText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.length >= 3) requestSuggestions(query) else loadSearchResults(query)
            }
        })
    }

    private fun setupBottomSheet() {
        val sheet = bottomSheet ?: return
        bottomSheetBehavior = BottomSheetBehavior.from(sheet)
        bottomSheetBehavior.isHideable = false
        bottomSheetBehavior.peekHeight = dpToPx(120)
        bottomSheetBehavior.maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) { updateControlsPosition(bottomSheet) }
            override fun onStateChanged(bottomSheet: View, newState: Int) { updateControlsPosition(bottomSheet) }
        })
        sheet.post { updateControlsPosition(sheet) }
    }

    private fun updateControlsPosition(sheet: View) {
        val controls = mapControls ?: return
        val parentHeight = (sheet.parent as? View)?.height ?: return
        val sheetTop = sheet.top
        val visibleSheetHeight = parentHeight - sheetTop
        currentSheetOffsetPx = visibleSheetHeight
        controls.translationY = -(visibleSheetHeight.toFloat() + dpToPx(8))
        controls.visibility = if (visibleSheetHeight > parentHeight * 0.75f) View.GONE else View.VISIBLE
    }

    /** Bottom inset (sheet offset) used so the camera centers above the sheet. */
    private fun currentInsets(): Rect {
        val expandedStates = setOf(
            SheetState.ROUTE_SELECTION, SheetState.ROUTE_DETAILS,
            SheetState.ACTIVE, SheetState.PLANNING
        )
        val sheetOffset = if (currentSheetState in expandedStates && ::bottomSheetBehavior.isInitialized) {
            bottomSheetBehavior.maxHeight
        } else {
            currentSheetOffsetPx
        }
        return Rect(0, 0, 0, sheetOffset)
    }

    private fun setupAdapters() {
        recentRecycler?.layoutManager = LinearLayoutManager(requireContext())
        recentRecycler?.adapter = recentAdapter
        searchResultsRecycler?.layoutManager = LinearLayoutManager(requireContext())
        searchResultsRecycler?.adapter = searchAdapter
        routeVariationsRecycler?.layoutManager = LinearLayoutManager(requireContext())
        routeVariationsRecycler?.adapter = alternativeAdapter
        detailsStepsRecycler?.layoutManager = LinearLayoutManager(requireContext())
        activeStepsRecycler?.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun setupMapControls() {
        btnZoomIn?.setOnClickListener { interactiveMap?.zoomIn() }
        btnZoomOut?.setOnClickListener { interactiveMap?.zoomOut() }
        btnMyLocation?.setOnClickListener {
            val loc = currentUserLocation
            if (loc != null) moveCameraTo(loc, 16f) else fetchUserLocation()
        }
    }

    private fun setupDestPreview() {
        destCopyButton?.setOnClickListener {
            val coords = destCoords?.text?.toString() ?: return@setOnClickListener
            val ctx = context ?: return@setOnClickListener
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("coordinates", coords))
            Toast.makeText(context, "Coordinates copied", Toast.LENGTH_SHORT).show()
        }
        destRouteButton?.setOnClickListener { startRoutePlanning() }
    }

    private fun setupRouteSelection() {
        routeDetailsButton?.setOnClickListener { showSheetState(SheetState.ROUTE_DETAILS) }
        routeStartButton?.setOnClickListener { startNavigation() }
        refreshRoutesButton?.setOnClickListener { loadAlternatives(currentSelectedMode) }
        routeMenuButton?.setOnClickListener { showRouteOptionsDialog() }
    }

    private fun updateTimeBadge() {
        routeMenuBadge?.visibility = if (selectedDepartureTime != null) View.VISIBLE else View.GONE
    }

    private fun showRouteOptionsDialog() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val view = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_route_options, null)
        dialog.setContentView(view)
        val bgColor = ContextCompat.getColor(ctx, R.color.nav_bg1)
        dialog.window?.navigationBarColor = bgColor
        (view.parent as? View)?.setBackgroundColor(bgColor)
        view.findViewById<View>(R.id.optionChangeRoute)?.setOnClickListener {
            dialog.dismiss(); showChangeRouteDialog()
        }
        view.findViewById<View>(R.id.optionChangeTime)?.setOnClickListener {
            dialog.dismiss(); showTimePickerDialog()
        }
        val depTime = selectedDepartureTime
        if (depTime != null) {
            val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val badge = view.findViewById<TextView>(R.id.optionChangeTimeBadge)
            badge?.text = tf.format(depTime)
            badge?.visibility = View.VISIBLE
        }
        dialog.show()
    }

    private fun showChangeRouteDialog() {
        val ctx = context ?: return
        if (editingRoutePoints == null) {
            val points = mutableListOf<RoutePoint>()
            points.add(RoutePoint(RoutePointType.ORIGIN, currentUserLocation, "My location"))
            for (wp in currentWaypoints) {
                points.add(RoutePoint(RoutePointType.WAYPOINT, wp, "%.4f, %.4f".format(wp.latitude, wp.longitude)))
            }
            points.add(RoutePoint(RoutePointType.DESTINATION, destinationPoint, destinationLabel))
            editingRoutePoints = points
        }
        val dialog = BottomSheetDialog(ctx)
        changeRouteDialog = dialog
        val view = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_change_route, null)
        dialog.setContentView(view)
        val bgColor = ContextCompat.getColor(ctx, R.color.nav_bg1)
        dialog.window?.navigationBarColor = bgColor
        (view.parent as? View)?.setBackgroundColor(bgColor)

        val recycler = view.findViewById<RecyclerView>(R.id.changeRoutePointsList)
        val routePointAdapter = createRoutePointAdapter(dialog)
        val touchHelper = ItemTouchHelper(RoutePointAdapter.DragCallback(routePointAdapter))
        routePointAdapter.itemTouchHelper = touchHelper
        recycler?.layoutManager = LinearLayoutManager(ctx)
        recycler?.adapter = routePointAdapter
        touchHelper.attachToRecyclerView(recycler)
        routePointAdapter.submitList(editingRoutePoints ?: emptyList())

        view.findViewById<View>(R.id.changeRouteAddPoint)?.setOnClickListener {
            editingPointIndex = Int.MAX_VALUE
            dialog.dismiss()
            showSheetState(SheetState.SEARCH)
        }
        val etaText = view.findViewById<TextView>(R.id.changeRouteEtaText)
        val etaIcon = view.findViewById<ImageView>(R.id.changeRouteEtaIcon)
        val currentRoute = currentAlternatives.firstOrNull()?.routeInfo
        if (currentRoute != null) {
            etaText?.text = currentRoute.etaFormatted
            etaIcon?.setImageResource(TransportMethodAdapter.iconForMode(currentSelectedMode))
        }
        view.findViewById<View>(R.id.changeRouteDoneButton)?.setOnClickListener {
            applyRoutePointChanges(); dialog.dismiss(); changeRouteDialog = null
        }
        dialog.setOnDismissListener {
            if (changeRouteDialog == dialog) {
                if (editingPointIndex == -1) editingRoutePoints = null
                changeRouteDialog = null
            }
        }
        dialog.show()
    }

    private fun createRoutePointAdapter(dialog: BottomSheetDialog): RoutePointAdapter {
        return RoutePointAdapter(
            onPointTap = { index ->
                editingPointIndex = index
                dialog.dismiss()
                showSheetState(SheetState.SEARCH)
            },
            onPointRemove = { index ->
                val pts = editingRoutePoints ?: return@RoutePointAdapter
                if (pts.size > 2) {
                    pts.removeAt(index)
                    for (i in pts.indices) {
                        val newType = when (i) {
                            0 -> RoutePointType.ORIGIN
                            pts.size - 1 -> RoutePointType.DESTINATION
                            else -> RoutePointType.WAYPOINT
                        }
                        if (pts[i].type != newType) pts[i] = pts[i].copy(type = newType)
                    }
                    dialog.dismiss()
                    showChangeRouteDialog()
                }
            }
        )
    }

    private fun applyRoutePointChanges() {
        val points = editingRoutePoints ?: return
        if (points.size < 2) return
        val origin = points.first()
        val destination = points.last()
        val waypoints = points.drop(1).dropLast(1).mapNotNull { it.point }
        if (origin.point != null) currentUserLocation = origin.point
        if (destination.point != null) {
            destinationPoint = destination.point
            destinationLabel = destination.label
            routeToText?.text = destination.label
        }
        currentWaypoints = waypoints
        editingRoutePoints = null
        editingPointIndex = -1
        loadAlternatives(currentSelectedMode)
    }

    private fun showTimePickerDialog() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val view = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_time_picker, null)
        dialog.setContentView(view)
        val bgColor = ContextCompat.getColor(ctx, R.color.nav_bg1)
        dialog.window?.navigationBarColor = bgColor
        (view.parent as? View)?.setBackgroundColor(bgColor)
        val calendar = Calendar.getInstance()
        selectedDepartureTime?.let { calendar.time = it }
        val dateFormat = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateValue = view.findViewById<TextView>(R.id.timeDateValue)
        val timeValue = view.findViewById<TextView>(R.id.timeTimeValue)
        dateValue?.text = dateFormat.format(calendar.time)
        timeValue?.text = timeFormat.format(calendar.time)
        dateValue?.setOnClickListener {
            android.app.DatePickerDialog(ctx,
                { _, year, month, day ->
                    calendar.set(Calendar.YEAR, year); calendar.set(Calendar.MONTH, month)
                    calendar.set(Calendar.DAY_OF_MONTH, day)
                    dateValue.text = dateFormat.format(calendar.time)
                },
                calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        timeValue?.setOnClickListener {
            android.app.TimePickerDialog(ctx,
                { _, hour, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hour); calendar.set(Calendar.MINUTE, minute)
                    timeValue.text = timeFormat.format(calendar.time)
                },
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true
            ).show()
        }
        view.findViewById<View>(R.id.timeResetButton)?.setOnClickListener {
            selectedDepartureTime = null; updateTimeBadge(); dialog.dismiss(); loadAlternatives(currentSelectedMode)
        }
        view.findViewById<View>(R.id.timeDoneButton)?.setOnClickListener {
            selectedDepartureTime = calendar.time; updateTimeBadge(); dialog.dismiss(); loadAlternatives(currentSelectedMode)
        }
        dialog.show()
    }

    private fun setupRouteDetails() {
        detailsBackButton?.setOnClickListener { showSheetState(SheetState.ROUTE_SELECTION) }
        detailsStartButton?.setOnClickListener { startNavigation() }
    }

    private fun setupActive() {
        stopButton?.setOnClickListener { navigationManager.stopJourney() }
        activeDetailsButton?.setOnClickListener { toggleActiveSteps() }
    }

    private fun setupBackHandling() {
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentSheetState) {
                    SheetState.SEARCH -> {
                        if (editingRoutePoints != null) {
                            editingPointIndex = -1
                            showSheetState(SheetState.ROUTE_SELECTION)
                            showChangeRouteDialog()
                        } else showSheetState(SheetState.IDLE)
                    }
                    SheetState.DESTINATION_PREVIEW -> {
                        clearDestinationMarker(); destinationPoint = null; showSheetState(SheetState.IDLE)
                    }
                    SheetState.ROUTE_SELECTION -> {
                        clearRoute(); showSheetState(SheetState.DESTINATION_PREVIEW)
                    }
                    SheetState.ROUTE_DETAILS -> showSheetState(SheetState.ROUTE_SELECTION)
                    else -> {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun fetchUserLocation() {
        val ctx = context ?: return
        val fusedClient = LocationServices.getFusedLocationProviderClient(ctx)
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location ->
                if (!isAdded) return@addOnSuccessListener
                if (location != null) {
                    val point = Point(location.latitude, location.longitude)
                    currentUserLocation = point
                    moveCameraTo(point, 14f)
                } else Log.w(TAG, "fetchUserLocation: fused returned null")
            }
            .addOnFailureListener { e -> Log.e(TAG, "fetchUserLocation: fused failed: ${e.message}") }
    }

    // -- Sheet state management --

    private fun showSheetState(state: SheetState) {
        if (currentSheetState == SheetState.PLANNING && state != SheetState.PLANNING) {
            planningHandler.removeCallbacks(planningTimeout)
        }
        if (currentSheetState == SheetState.SEARCH && state != SheetState.SEARCH) {
            bottomSheetBehavior.maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
            bottomSheet?.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
            bottomSheet?.requestLayout()
        }
        currentSheetState = state
        backCallback.isEnabled = state != SheetState.IDLE && state != SheetState.ACTIVE

        idleView?.visibility = View.GONE
        searchView?.visibility = View.GONE
        destPreviewView?.visibility = View.GONE
        planningView?.visibility = View.GONE
        routeSelectionView?.visibility = View.GONE
        routeDetailsView?.visibility = View.GONE
        activeView?.visibility = View.GONE

        when (state) {
            SheetState.IDLE -> {
                idleView?.visibility = View.VISIBLE
                bottomSheetBehavior.isDraggable = true
                bottomSheetBehavior.peekHeight = dpToPx(120)
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                hideKeyboard()
                loadRecents("")
            }
            SheetState.SEARCH -> {
                searchView?.visibility = View.VISIBLE
                bottomSheetBehavior.maxHeight = resources.displayMetrics.heightPixels
                bottomSheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
                bottomSheet?.requestLayout()
                bottomSheetBehavior.isDraggable = false
                bottomSheetBehavior.peekHeight = resources.displayMetrics.heightPixels
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                searchEditText?.setText("")
                searchEditText?.requestFocus()
                showKeyboard(searchEditText)
                loadSearchResults("")
            }
            SheetState.DESTINATION_PREVIEW -> {
                destPreviewView?.visibility = View.VISIBLE
                bottomSheetBehavior.isDraggable = true
                bottomSheetBehavior.peekHeight = dpToPx(220)
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                hideKeyboard()
            }
            SheetState.PLANNING -> {
                planningView?.visibility = View.VISIBLE
                bottomSheetBehavior.isDraggable = false
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                planningHandler.removeCallbacks(planningTimeout)
                planningHandler.postDelayed(planningTimeout, 30_000L)
            }
            SheetState.ROUTE_SELECTION -> {
                routeSelectionView?.visibility = View.VISIBLE
                bottomSheetBehavior.isDraggable = true
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
            SheetState.ROUTE_DETAILS -> {
                routeDetailsView?.visibility = View.VISIBLE
                bottomSheetBehavior.isDraggable = true
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                populateRouteDetails()
            }
            SheetState.ACTIVE -> {
                activeView?.visibility = View.VISIBLE
                bottomSheetBehavior.isDraggable = true
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                interactiveMap?.setMyLocationEnabled(true)
            }
        }

        bottomSheet?.postDelayed({
            bottomSheet?.let { updateControlsPosition(it) }
            // Active journey follows the user (onLocationUpdated); only re-fit the
            // route when not actively navigating.
            if (navigationManager.getState() == NavigationManager.State.ACTIVE) {
                currentUserLocation?.let { moveCameraTo(it, zoomForRadiusMeters(FOLLOW_RADIUS_METERS, it.latitude)) }
            } else {
                lastCameraFitPoints?.let { moveCameraToFit(it) }
            }
        }, 350)
    }

    // -- Search & Suggestions --

    private fun loadRecents(query: String) = loadRecentsInto(query, recentAdapter)
    private fun loadSearchResults(query: String) = loadRecentsInto(query, searchAdapter)

    private fun loadRecentsInto(query: String, adapter: SuggestAdapter) {
        val dao = recentDao ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val entities = if (query.isEmpty()) dao.getRecent() else dao.search(query)
            val items = entities.map { entity ->
                val dist = currentUserLocation?.let { loc ->
                    formatDistance(haversineMeters(loc.latitude, loc.longitude, entity.lat, entity.lng))
                }
                SuggestItem(entity.title, entity.subtitle, Point(entity.lat, entity.lng), true, dist)
            }
            adapter.submitList(items)
        }
    }

    private fun requestSuggestions(query: String) {
        loadSearchResults(query)
        val center = currentUserLocation ?: Point(55.7539, 37.6208)
        MapProviders.active.placeSuggest.suggest(query, center) { suggestItems ->
            if (!isAdded) return@suggest
            val dao = recentDao ?: return@suggest
            viewLifecycleOwner.lifecycleScope.launch {
                val recents = dao.search(query).map { entity ->
                    val dist = currentUserLocation?.let { loc ->
                        formatDistance(haversineMeters(loc.latitude, loc.longitude, entity.lat, entity.lng))
                    }
                    SuggestItem(entity.title, entity.subtitle, Point(entity.lat, entity.lng), true, dist)
                }
                searchAdapter.submitList(recents + suggestItems)
            }
        }
    }

    private fun onSuggestItemChosen(item: SuggestItem) {
        hideKeyboard()
        val point = item.point
        if (editingRoutePoints != null && editingPointIndex >= 0) {
            if (point != null) applyEditingPoint(point, item.title)
            else geocodeForRouteEditing(item.title)
            return
        }
        if (point != null) setDestination(point, item.title, item.subtitle)
        else geocodeAndSetDestination(item.title)
    }

    private fun applyEditingPoint(point: Point, label: String) {
        val pts = editingRoutePoints ?: return
        if (editingPointIndex == Int.MAX_VALUE) {
            val insertIndex = (pts.size - 1).coerceAtLeast(1)
            pts.add(insertIndex, RoutePoint(RoutePointType.WAYPOINT, point, label))
        } else if (editingPointIndex in pts.indices) {
            pts[editingPointIndex] = pts[editingPointIndex].copy(point = point, label = label)
        }
        editingPointIndex = -1
        showChangeRouteDialog()
    }

    private fun geocodeForRouteEditing(query: String) {
        showSheetState(SheetState.PLANNING)
        MapProviders.active.geocoder.geocode(query) { point ->
            if (!isAdded) return@geocode
            if (point != null) applyEditingPoint(point, query)
            else {
                Log.w(TAG, "Geocoding returned no results for route edit: $query")
                editingPointIndex = -1
                showChangeRouteDialog()
            }
        }
    }

    private fun geocodeAndSetDestination(query: String) {
        showSheetState(SheetState.PLANNING)
        MapProviders.active.geocoder.geocode(query) { point ->
            if (!isAdded) return@geocode
            if (point != null) setDestination(point, query)
            else {
                Log.w(TAG, "Geocoding returned no results for: $query")
                showSheetState(SheetState.IDLE)
            }
        }
    }

    // -- Destination handling --

    private fun setDestination(point: Point, label: String, subtitle: String = "") {
        destinationPoint = point
        destinationLabel = label
        placeDestinationMarker(point)
        saveRecentDestination(label, subtitle, point)

        val coordStr = "%.6f, %.6f".format(point.latitude, point.longitude)
        destName?.text = label
        destCoords?.text = coordStr
        destCityPostal?.visibility = View.GONE
        destApproxEtaRow?.visibility = View.GONE

        MapProviders.active.geocoder.reverseGeocode(point) { result ->
            if (!isAdded) return@reverseGeocode
            if (result != null) {
                val parts = listOfNotNull(result.city, result.postalCode).joinToString(", ")
                if (parts.isNotEmpty()) {
                    destCityPostal?.text = parts
                    destCityPostal?.visibility = View.VISIBLE
                }
                if (result.name.isNotBlank() && label == "Dropped pin") {
                    destName?.text = result.name
                    destinationLabel = result.name
                }
            }
        }

        currentUserLocation?.let { from ->
            MapProviders.active.routing.queryMode(from, point, TransportMode.TRANSIT) { method ->
                if (!isAdded) return@queryMode
                if (method != null) {
                    val distKm = method.distanceMeters / 1000.0
                    val distText = if (distKm >= 1.0) "%.1f km".format(distKm) else "${method.distanceMeters} m"
                    destApproxEtaText?.text = method.etaFormatted
                    destApproxDistText?.text = distText
                    destApproxEtaRow?.visibility = View.VISIBLE
                }
            }
        }

        val cameraPoints = mutableListOf(point)
        currentUserLocation?.let { cameraPoints.add(it) }
        moveCameraToFit(cameraPoints)
        showSheetState(SheetState.DESTINATION_PREVIEW)
    }

    private fun saveRecentDestination(title: String, subtitle: String, point: Point) {
        val dao = recentDao ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val roundedLat = round(point.latitude * 10000) / 10000
            val roundedLng = round(point.longitude * 10000) / 10000
            dao.upsert(
                RecentDestinationEntity(
                    title = title, subtitle = subtitle,
                    lat = point.latitude, lng = point.longitude,
                    roundedLat = roundedLat, roundedLng = roundedLng,
                    lastUsedAt = System.currentTimeMillis()
                )
            )
            dao.pruneOld()
        }
    }

    // -- Route planning --

    @android.annotation.SuppressLint("MissingPermission")
    private fun startRoutePlanning() {
        val dest = destinationPoint ?: return
        val ctx = context ?: return
        showSheetState(SheetState.PLANNING)
        val from = currentUserLocation
        if (from != null) {
            doRoutePlanning(from, dest)
        } else {
            val fusedClient = LocationServices.getFusedLocationProviderClient(ctx)
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { location ->
                    if (!isAdded) return@addOnSuccessListener
                    if (location != null) {
                        val point = Point(location.latitude, location.longitude)
                        currentUserLocation = point
                        doRoutePlanning(point, dest)
                    } else {
                        Toast.makeText(context, "Cannot determine location", Toast.LENGTH_SHORT).show()
                        showSheetState(SheetState.DESTINATION_PREVIEW)
                    }
                }
                .addOnFailureListener { e ->
                    if (!isAdded) return@addOnFailureListener
                    Log.e(TAG, "startRoutePlanning: fused failed: ${e.message}")
                    Toast.makeText(context, "Location error", Toast.LENGTH_SHORT).show()
                    showSheetState(SheetState.DESTINATION_PREVIEW)
                }
        }
    }

    private fun doRoutePlanning(from: Point, to: Point) {
        navigationManager.planJourney(from, to) { _ -> if (!isAdded) return@planJourney }
    }

    // -- Route selection --

    private fun populateRouteSelection(methods: List<TransportMethodInfo>) {
        val ctx = context ?: return
        cachedMethods = methods
        routeFromText?.text = "My location"
        routeToText?.text = destinationLabel
        val chipGroup = modeChipGroup ?: return
        chipGroup.removeAllViews()
        val sorted = methods.sortedBy { it.etaSeconds }
        val prefs = ctx.getSharedPreferences("nav_prefs", android.content.Context.MODE_PRIVATE)
        val savedModeName = prefs.getString("preferred_transport_mode", null)
        val savedMode = savedModeName?.let { name -> TransportMode.entries.find { it.name == name } }
        val fastestMode = if (savedMode != null && sorted.any { it.mode == savedMode }) savedMode
                          else sorted.firstOrNull()?.mode ?: TransportMode.TRANSIT
        val bgStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ContextCompat.getColor(ctx, R.color.nav_orange), ContextCompat.getColor(ctx, R.color.nav_bg1))
        )
        val textStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ContextCompat.getColor(ctx, R.color.nav_bg), ContextCompat.getColor(ctx, R.color.nav_fg))
        )
        for (method in sorted) {
            val chip = Chip(ctx).apply {
                text = "${modeLabel(method.mode)} ${method.etaFormatted}"
                textSize = 13f
                isCheckable = true
                tag = method.mode
                setChipIconResource(TransportMethodAdapter.iconForMode(method.mode))
                chipIconSize = dpToPx(18).toFloat()
                isChipIconVisible = true
                chipBackgroundColor = bgStates
                setTextColor(textStates)
                chipIconTint = textStates
            }
            chipGroup.addView(chip)
            if (method.mode == fastestMode) chip.isChecked = true
        }
        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedChip = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it) }
            val mode = checkedChip?.tag as? TransportMode ?: return@setOnCheckedStateChangeListener
            onModeChipSelected(mode)
        }
        currentSelectedMode = fastestMode
        showSheetState(SheetState.ROUTE_SELECTION)
        loadAlternatives(fastestMode)
    }

    private fun onModeChipSelected(mode: TransportMode) {
        if (mode == currentSelectedMode && currentAlternatives.isNotEmpty()) return
        currentSelectedMode = mode
        val ctx = context ?: return
        ctx.getSharedPreferences("nav_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("preferred_transport_mode", mode.name).apply()
        loadAlternatives(mode)
    }

    private fun loadAlternatives(mode: TransportMode) {
        routeDetailsButton?.visibility = if (mode == TransportMode.DRIVING) View.GONE else View.VISIBLE
        val from = currentUserLocation ?: return
        val to = destinationPoint ?: return
        navigationManager.planJourneyAlternatives(from, to, mode, currentWaypoints, selectedDepartureTime) { alternatives ->
            if (!isAdded) return@planJourneyAlternatives
            currentAlternatives = alternatives
            alternativeAdapter.submitList(alternatives)
            alternatives.firstOrNull()?.let { alt ->
                navigationManager.selectAlternative(alt)
                drawRoute(alt.routeInfo)
            }
        }
    }

    private fun onAlternativeSelected(alt: RouteAlternative) {
        navigationManager.selectAlternative(alt)
        drawRoute(alt.routeInfo)
    }

    private fun drawRoute(route: RouteInfo) {
        if (route.mode == TransportMode.TRANSIT && route.transitSections.isNotEmpty()) {
            showTransitRoute(route.transitSections)
        } else {
            showRoute(route.polylinePoints)
        }
    }

    // -- Route details --

    private fun populateRouteDetails() {
        val route = navigationManager.getCurrentRoute() ?: return
        detailsModeIcon?.setImageResource(TransportMethodAdapter.iconForMode(route.mode))
        detailsEtaText?.text = route.etaFormatted
        detailsDistanceText?.text = route.distanceFormatted
        if (route.transitSections.isNotEmpty()) {
            detailsStepsRecycler?.adapter = detailsTransitAdapter
            detailsTransitAdapter.submitList(route.transitSections)
        } else if (route.instructions.isNotEmpty()) {
            detailsStepsRecycler?.adapter = detailsInstructionAdapter
            detailsInstructionAdapter.submitList(route.instructions)
        }
    }

    // -- Start navigation --

    private fun startNavigation() {
        if (currentAlternatives.isEmpty()) {
            Toast.makeText(context, "No route selected", Toast.LENGTH_SHORT).show()
            return
        }
        navigationManager.startFromPreview { _, _ -> }
    }

    // -- NavigationStateListener --

    override fun onStateChanged(state: NavigationManager.State) {
        if (!isAdded) return
        when (state) {
            NavigationManager.State.IDLE -> {
                clearRoute(); clearDestinationMarker()
                interactiveMap?.setFollowMode(false, 0f, Rect(0, 0, 0, 0))
                interactiveMap?.setMyLocationEnabled(true)
                destinationPoint = null
                hideActiveSteps()
                showSheetState(SheetState.IDLE)
            }
            NavigationManager.State.PLANNING -> {
                if (currentSheetState != SheetState.PLANNING && currentSheetState != SheetState.ROUTE_SELECTION) {
                    showSheetState(SheetState.PLANNING)
                }
            }
            NavigationManager.State.PREVIEW -> {}
            NavigationManager.State.ACTIVE -> {
                showSheetState(SheetState.ACTIVE)
                interactiveMap?.setMyLocationEnabled(true)
                val route = navigationManager.getCurrentRoute()
                if (route != null) {
                    drawRoute(route)
                    activeEtaText?.text = com.repository.navigation.util.DurationFormat.format(route.etaSeconds)
                    if (route.instructions.isNotEmpty()) {
                        activeInstructionText?.text = route.instructions.first().text
                    }
                }
                // Map self-follows the user (centres on the same fix that draws the
                // marker) at the close follow zoom, lifted above the active sheet.
                val followLat = currentUserLocation?.latitude ?: 41.0
                interactiveMap?.setFollowMode(
                    true, zoomForRadiusMeters(FOLLOW_RADIUS_METERS, followLat),
                    Rect(0, 0, 0, currentSheetOffsetPx)
                )
                populateActiveSteps()
            }
        }
    }

    override fun onMethodsReady(methods: List<TransportMethodInfo>) {
        if (!isAdded) return
        populateRouteSelection(methods)
    }

    override fun onRoutePreviewReady(route: RouteInfo) {}

    override fun onRouteAlternativesReady(mode: TransportMode, alternatives: List<RouteAlternative>) {}

    override fun onEtaUpdated(etaSeconds: Long) {
        activeEtaText?.text = com.repository.navigation.util.DurationFormat.format(etaSeconds)
    }

    override fun onInstructionChanged(instruction: String) {
        activeInstructionText?.text = instruction
    }

    override fun onRouteUpdated(points: List<Point>) {
        val route = navigationManager.getCurrentRoute()
        if (route != null && route.mode == TransportMode.TRANSIT && route.transitSections.isNotEmpty()) {
            showTransitRoute(route.transitSections)
        } else {
            showRoute(points)
        }
    }

    override fun onStepChanged(stepIndex: Int) {
        if (!isAdded) return
        val route = navigationManager.getCurrentRoute() ?: return
        if (route.transitSections.isNotEmpty()) {
            activeTransitAdapter.setCurrentIndex(stepIndex)
            // Surface the current segment under the ETA so the user sees what they
            // are doing right now, not just the highlighted row in the list.
            route.transitSections.getOrNull(stepIndex)?.let {
                activeInstructionText?.text = currentSegmentLabel(it)
            }
        } else {
            activeInstructionAdapter.setCurrentIndex(stepIndex)
            route.instructions.getOrNull(stepIndex)?.let {
                activeInstructionText?.text = it.text
            }
        }
        activeStepsRecycler?.smoothScrollToPosition(stepIndex)
    }

    /** One-line description of the current transit segment shown under the ETA. */
    private fun currentSegmentLabel(section: TransitSection): String {
        if (section.type == TransitSectionType.WALK) {
            val dist = section.distanceFormatted.takeIf { it.isNotEmpty() }
            return if (dist != null) "Walk $dist" else "Walk"
        }
        val typeName = when (section.type) {
            TransitSectionType.BUS -> "Bus"
            TransitSectionType.METRO -> "Metro"
            TransitSectionType.TRAM -> "Tram"
            TransitSectionType.TROLLEYBUS -> "Trolleybus"
            TransitSectionType.TRAIN -> "Train"
            TransitSectionType.SUBURBAN -> "Suburban"
            TransitSectionType.HIGH_SPEED_TRAIN -> "High-speed train"
            TransitSectionType.FERRY -> "Ferry"
            TransitSectionType.CABLE_CAR -> "Cable car"
            TransitSectionType.FUNICULAR -> "Funicular"
            TransitSectionType.GONDOLA -> "Gondola"
            TransitSectionType.SHARE_TAXI -> "Share taxi"
            TransitSectionType.WALK -> "Walk"
            TransitSectionType.OTHER -> "Transit"
        }
        val line = section.lineShortName ?: section.lineName
        val head = if (line != null) "$typeName $line" else typeName
        val to = section.alightStop
        return if (to != null) "$head -> $to" else head
    }

    override fun onLocationUpdated(lat: Double, lng: Double) {
        currentUserLocation = Point(lat, lng)
        // The interactive map self-follows in ACTIVE state (setFollowMode), centring
        // on the same fix that draws the user marker so the two never desync. Just
        // keep the follow insets current as the sheet moves.
        if (navigationManager.getState() == NavigationManager.State.ACTIVE) {
            interactiveMap?.setFollowMode(
                true, zoomForRadiusMeters(FOLLOW_RADIUS_METERS, lat),
                Rect(0, 0, 0, currentSheetOffsetPx)
            )
        }
    }

    /**
     * Web-Mercator zoom that shows roughly [radiusMeters] from the map center to
     * the screen edge (so the visible span across the map width is ~2*radius).
     * Matches Google/Yandex tile zoom: metersPerPixel = 156543.03392 * cos(lat) / 2^zoom.
     */
    private fun zoomForRadiusMeters(radiusMeters: Double, lat: Double): Float {
        val widthPx = (mapContainer?.width ?: resources.displayMetrics.widthPixels)
            .coerceAtLeast(1)
        val targetMpp = (radiusMeters * 2.0) / widthPx
        val zoom = Math.log(156543.03392 * Math.cos(Math.toRadians(lat)) / targetMpp) / Math.log(2.0)
        return zoom.toFloat().coerceIn(3f, 20f)
    }

    override fun onError(message: String) {
        if (!isAdded) return
        Toast.makeText(context ?: return, message, Toast.LENGTH_LONG).show()
    }

    // -- Active journey steps --

    private fun populateActiveSteps() {
        val route = navigationManager.getCurrentRoute() ?: return
        if (route.transitSections.isNotEmpty()) {
            activeStepsRecycler?.adapter = activeTransitAdapter
            activeTransitAdapter.submitList(route.transitSections)
        } else if (route.instructions.isNotEmpty()) {
            activeStepsRecycler?.adapter = activeInstructionAdapter
            activeInstructionAdapter.submitList(route.instructions)
        }
    }

    private fun toggleActiveSteps() {
        stepsVisible = !stepsVisible
        activeStepsRecycler?.visibility = if (stepsVisible) View.VISIBLE else View.GONE
        activeDetailsButton?.text = if (stepsVisible) "Hide" else "Details"
        if (stepsVisible) bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun hideActiveSteps() {
        stepsVisible = false
        activeStepsRecycler?.visibility = View.GONE
        activeDetailsButton?.text = "Details"
    }

    // -- Route drawing (delegated to the provider's InteractiveMap) --

    private fun showRoute(points: List<Point>) {
        if (points.size < 2) return
        interactiveMap?.addRoute(points)
        // In an active journey the camera follows the user (see onLocationUpdated),
        // so only fit-to-route when previewing/planning.
        if (navigationManager.getState() != NavigationManager.State.ACTIVE) moveCameraToFit(points)
    }

    private fun showTransitRoute(sections: List<TransitSection>) {
        interactiveMap?.addTransit(sections)
        val allPoints = sections.flatMap { it.polylinePoints }
        if (allPoints.isNotEmpty() &&
            navigationManager.getState() != NavigationManager.State.ACTIVE) {
            moveCameraToFit(allPoints)
        }
    }

    private fun clearRoute() {
        interactiveMap?.clearRoute()
        lastCameraFitPoints = null
    }

    private fun placeDestinationMarker(point: Point) {
        val icon = vectorToBitmap(R.drawable.ic_destination_pin)
        interactiveMap?.setDestination(point, destinationLabel, icon)
    }

    private fun vectorToBitmap(drawableRes: Int): android.graphics.Bitmap? {
        val ctx = context ?: return null
        val drawable = ContextCompat.getDrawable(ctx, drawableRes) ?: return null
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else dpToPx(24)
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else dpToPx(36)
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun clearDestinationMarker() {
        interactiveMap?.clearDestination()
    }

    private fun moveCameraTo(point: Point, zoom: Float) {
        // Use the ACTUAL visible sheet height (not the expanded maxHeight) so the
        // followed user marker is lifted just above the collapsed/peek sheet rather
        // than over-pushed off-screen.
        interactiveMap?.moveCamera(point, zoom, Rect(0, 0, 0, currentSheetOffsetPx))
    }

    private fun moveCameraToFit(points: List<Point>) {
        if (points.isEmpty()) return
        lastCameraFitPoints = points
        bottomSheet?.let { updateControlsPosition(it) }
        interactiveMap?.moveCameraToFit(points, currentInsets())
    }

    // -- Helpers --

    private fun modeLabel(mode: TransportMode): String = when (mode) {
        TransportMode.WALKING -> "Walk"
        TransportMode.TRANSIT -> "Transit"
        TransportMode.DRIVING -> "Car"
        TransportMode.BICYCLE -> "Bicycle"
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "${meters.toInt()} m"

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun hideKeyboard() {
        val ctx = context ?: return
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val view = activity?.currentFocus ?: View(ctx)
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showKeyboard(view: View?) {
        view?.postDelayed({
            val ctx = context ?: return@postDelayed
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    // -- Lifecycle --

    override fun onStart() {
        super.onStart()
        interactiveMap?.onStart()
    }

    override fun onResume() {
        super.onResume()
        rebuildMapIfProviderChanged()
        interactiveMap?.onResume()
    }

    override fun onPause() {
        interactiveMap?.onPause()
        super.onPause()
    }

    override fun onStop() {
        interactiveMap?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        interactiveMap?.onLowMemory()
    }

    override fun onDestroyView() {
        clearRoute()
        clearDestinationMarker()
        interactiveMap?.onDestroy()
        interactiveMap = null
        MapProviders.active.placeSuggest.reset()
        if (navigationManager.stateListener === this) navigationManager.stateListener = null
        planningHandler.removeCallbacks(planningTimeout)
        recentDao = null
        sensorManager?.unregisterListener(compassListener)
        sensorManager = null
        mapContainer = null
        bottomSheet = null
        mapControls = null
        idleView = null
        idleSearchPill = null
        recentRecycler = null
        searchView = null
        searchBackButton = null
        searchEditText = null
        searchResultsRecycler = null
        destPreviewView = null
        destName = null
        destCityPostal = null
        destCoords = null
        destCopyButton = null
        destApproxEtaRow = null
        destApproxEtaText = null
        destApproxDistText = null
        destRouteButton = null
        planningView = null
        routeSelectionView = null
        routeFromText = null
        routeToText = null
        modeChipGroup = null
        routeVariationsRecycler = null
        routeDetailsButton = null
        routeStartButton = null
        routeDetailsView = null
        detailsModeIcon = null
        detailsEtaText = null
        detailsDistanceText = null
        detailsStepsRecycler = null
        detailsBackButton = null
        detailsStartButton = null
        activeView = null
        activeEtaText = null
        activeInstructionText = null
        activeStepsRecycler = null
        activeDetailsButton = null
        stopButton = null
        btnZoomIn = null
        btnZoomOut = null
        btnMyLocation = null
        super.onDestroyView()
    }
}
