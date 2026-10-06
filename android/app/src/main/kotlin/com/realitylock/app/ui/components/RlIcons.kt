package com.realitylock.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Biotech
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every icon the app uses, named by what it MEANS rather than by its glyph.
 *
 * One place, so a screen asks for `RlIcons.Hash` and the choice of picture can be
 * changed — or corrected, if a name stops resolving in a newer icon set — without
 * touching the screens. Meaning-based names also keep one concept to one picture
 * across the app, which is most of what makes an icon vocabulary learnable.
 */
object RlIcons {
    val Capture: ImageVector = Icons.Rounded.CameraAlt
    val History: ImageVector = Icons.Rounded.History
    val Analyze: ImageVector = Icons.Rounded.Biotech
    val Device: ImageVector = Icons.Rounded.Memory

    val Pass: ImageVector = Icons.Rounded.CheckCircle
    val Fail: ImageVector = Icons.Rounded.Cancel
    val Warn: ImageVector = Icons.Rounded.Warning
    val Unknown: ImageVector = Icons.Rounded.HelpOutline
    val Info: ImageVector = Icons.Rounded.Info
    val Close: ImageVector = Icons.Rounded.Close
    val More: ImageVector = Icons.Rounded.MoreVert
    val Expand: ImageVector = Icons.Rounded.ExpandMore
    val Collapse: ImageVector = Icons.Rounded.ExpandLess
    val Refresh: ImageVector = Icons.Rounded.Refresh
    val Delete: ImageVector = Icons.Rounded.Delete

    val Online: ImageVector = Icons.Rounded.Wifi
    val Offline: ImageVector = Icons.Rounded.WifiOff
    val CloudDone: ImageVector = Icons.Rounded.CloudDone
    val CloudUp: ImageVector = Icons.Rounded.CloudUpload
    val CloudOff: ImageVector = Icons.Rounded.CloudOff
    val Syncing: ImageVector = Icons.Rounded.Sync
    val SyncFailed: ImageVector = Icons.Rounded.SyncProblem

    val Location: ImageVector = Icons.Rounded.LocationOn
    val GpsFix: ImageVector = Icons.Rounded.GpsFixed
    val GpsOff: ImageVector = Icons.Rounded.GpsOff
    val Satellite: ImageVector = Icons.Rounded.SatelliteAlt
    val Motion: ImageVector = Icons.Rounded.Sensors
    val Steady: ImageVector = Icons.Rounded.Speed
    val Time: ImageVector = Icons.Rounded.Schedule

    val Photo: ImageVector = Icons.Rounded.Photo
    val Hash: ImageVector = Icons.Rounded.Tag
    val Fingerprint: ImageVector = Icons.Rounded.Fingerprint
    val Sign: ImageVector = Icons.Rounded.Edit
    val Key: ImageVector = Icons.Rounded.VpnKey
    val Lock: ImageVector = Icons.Rounded.Lock
    val Shield: ImageVector = Icons.Rounded.Shield
    val ShieldGood: ImageVector = Icons.Rounded.GppGood
    val Verified: ImageVector = Icons.Rounded.Verified
    val VerifiedUser: ImageVector = Icons.Rounded.VerifiedUser
    val Security: ImageVector = Icons.Rounded.Security
    val Tree: ImageVector = Icons.Rounded.AccountTree
    val Record: ImageVector = Icons.Rounded.Description
    val Folder: ImageVector = Icons.Rounded.Folder
    val Backup: ImageVector = Icons.Rounded.Backup

    val Phone: ImageVector = Icons.Rounded.Smartphone
    val PhoneAndroid: ImageVector = Icons.Rounded.PhoneAndroid
    val Science: ImageVector = Icons.Rounded.Science
    val Face: ImageVector = Icons.Rounded.Face
    val Bolt: ImageVector = Icons.Rounded.Bolt
    val Blocked: ImageVector = Icons.Rounded.Block
    val Legal: ImageVector = Icons.Rounded.Gavel
    val Privacy: ImageVector = Icons.Rounded.PrivacyTip
    val Gyro: ImageVector = Icons.Rounded.ScreenRotation
    val StrongBox: ImageVector = Icons.Rounded.EnhancedEncryption
}
