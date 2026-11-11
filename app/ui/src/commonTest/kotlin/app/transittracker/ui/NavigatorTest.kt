package app.transittracker.ui

import app.transittracker.model.Mode
import app.transittracker.model.ServiceRef
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.nav.Navigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.nav.Tab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigatorTest {
    private val ref = ServiceRef("T9N-0730", "20261111")
    private val t9 = PageTransition.vehiclePass(Mode.TRAIN, "D11F2F")

    @Test
    fun pushAndBackPlayTheSameTransitionInReverse() {
        val nav = Navigator()
        assertEquals(Route.Trips, nav.current.route)
        assertFalse(nav.canGoBack)
        nav.push(Route.Service(ref, "S-P3", "C-P3"), t9)
        assertEquals(Route.Service(ref, "S-P3", "C-P3"), nav.current.route)
        assertEquals(t9, nav.lastChange!!.transition)
        assertFalse(nav.lastChange!!.reverse)
        nav.push(Route.Tracking(ref))
        assertEquals(PageTransition.Doors, nav.lastChange!!.transition, "into tracking: doors")
        assertTrue(nav.back())
        assertEquals(PageTransition.Doors, nav.lastChange!!.transition, "out of tracking: doors")
        assertTrue(nav.lastChange!!.reverse)
        assertTrue(nav.back())
        assertEquals(t9, nav.lastChange!!.transition)
        assertTrue(nav.lastChange!!.reverse)
        assertEquals(Route.Trips, nav.current.route)
        assertFalse(nav.back(), "back on the Trips root leaves the app")
    }

    @Test
    fun defaultPushIsAVehiclePassAndMapIsDoors() {
        val nav = Navigator()
        nav.push(Route.PlanJourney())
        assertEquals(PageTransition.DefaultVehiclePass, nav.lastChange!!.transition)
        nav.switchTab(Tab.MAP)
        assertEquals(PageTransition.Doors, nav.lastChange!!.transition, "to the map tab: doors")
        nav.switchTab(Tab.ALERTS)
        assertEquals(PageTransition.Doors, nav.lastChange!!.transition, "leaving the map: doors")
        nav.switchTab(Tab.NOTIFY)
        assertEquals(PageTransition.SplitFlap, nav.lastChange!!.transition, "tab switch: split-flap")
    }

    @Test
    fun tabsKeepTheirOwnStacks() {
        val nav = Navigator()
        nav.push(Route.Service(ref), t9)
        val serviceEntry = nav.current
        nav.switchTab(Tab.SETTINGS)
        assertEquals(Route.Settings, nav.current.route)
        nav.push(Route.SavedPlaces)
        nav.switchTab(Tab.TRIPS)
        assertEquals(serviceEntry, nav.current, "Trips stack kept, same entry (same saved state key)")
        nav.switchTab(Tab.SETTINGS)
        assertEquals(Route.SavedPlaces, nav.current.route)
        // Tapping the current tab returns to its root.
        nav.switchTab(Tab.SETTINGS)
        assertEquals(Route.Settings, nav.current.route)
        // Back on another tab's root returns to Trips.
        assertTrue(nav.back())
        assertEquals(Tab.TRIPS, nav.currentTab)
    }

    @Test
    fun stateSurvivesSaveAndRestore() {
        val nav = Navigator()
        nav.push(Route.Service(ref, "S-P3"), t9)
        nav.push(Route.WatchEditor(fromService = ref, boardStopId = "S-P3"))
        nav.switchTab(Tab.NOTIFY)
        val saved = Navigator.Saver.run { androidx.compose.runtime.saveable.SaverScope { true }.save(nav) }!!
        val restored = Navigator.Saver.restore(saved)!!
        assertEquals(Tab.NOTIFY, restored.currentTab)
        restored.switchTab(Tab.TRIPS)
        assertEquals(Route.WatchEditor(fromService = ref, boardStopId = "S-P3"), restored.current.route)
        val ids = nav.allEntries.map { it.id }
        assertEquals(ids.sorted(), restored.allEntries.map { it.id }.sorted(), "entry ids (saved-state keys) kept")
        assertTrue(restored.back())
        assertEquals(t9, (restored.backStack.last().enter), "the transition that opened the service is kept")
        // New entries do not reuse ids.
        restored.push(Route.ManageRoutes)
        assertTrue(restored.current.id !in ids)
    }

    @Test
    fun notificationsOpenWithACut() {
        val nav = Navigator()
        nav.switchTab(Tab.SETTINGS)
        nav.push(Route.SavedPlaces)
        nav.openFromNotification(Route.Service(ref))
        assertEquals(Tab.TRIPS, nav.currentTab)
        assertEquals(Route.Service(ref), nav.current.route)
        assertEquals(PageTransition.Cut, nav.lastChange!!.transition)
        assertEquals(2, nav.backStack.size)
        nav.openFromNotification(Route.Alerts)
        assertEquals(Tab.ALERTS, nav.currentTab)
        assertEquals(PageTransition.Cut, nav.lastChange!!.transition)
    }

    @Test
    fun onboardingThenDoorsHome() {
        val nav = Navigator()
        nav.push(Route.Service(ref))
        nav.startOnboarding()
        assertEquals(Route.Onboarding, nav.current.route)
        assertEquals(listOf(Route.Trips, Route.Onboarding), nav.backStack.map { it.route })
        nav.push(Route.PlanJourney(fromOnboarding = true))
        nav.resetTo(Tab.TRIPS, PageTransition.Doors)
        assertEquals(listOf(Route.Trips), nav.backStack.map { it.route })
        assertEquals(PageTransition.Doors, nav.lastChange!!.transition)
    }
}
