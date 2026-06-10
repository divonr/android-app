/**
 * ui/icons.ts — Single import point for all Material-Design icons used across the app.
 * Sourced from react-icons/md (mirrors the Icons.Default.* + Icons.AutoMirrored.Filled.*
 * icons in the Android Compose screens).
 *
 * Screens import from here so renaming/swapping is a one-file change.
 */

import React from 'react'
import type { IconType } from 'react-icons'
import { MdArrowBack as MdArrowBackLtr } from 'react-icons/md'

/**
 * Auto-mirrored back arrow — the document is RTL (dir="rtl" on <html>), so the
 * back arrow must point outward (right), like Icons.AutoMirrored.Filled.ArrowBack.
 */
export const MdArrowBack: IconType = ({ style, ...rest }) => (
  <MdArrowBackLtr style={{ transform: 'scaleX(-1)', ...style }} {...rest} />
)

export {
  // Navigation
  MdChevronLeft,
  MdChevronRight,
  MdExpandMore,
  MdExpandLess,
  MdClose,
  // Actions
  MdAdd,
  MdSend,
  MdStop,
  MdCheck,
  MdEdit,
  MdDelete,
  MdContentCopy,
  MdShare,
  MdRefresh,
  MdDownload,
  MdFileDownload,
  MdRemove,
  MdStar,
  MdStarBorder,
  MdSort,
  MdAttachMoney,
  // Search
  MdSearch,
  // Settings / config
  MdSettings,
  MdBuild,
  // Thinking / temperature / tools
  MdLightbulb,
  MdThermostat,
  MdExtension,
  // Text direction
  MdFormatAlignRight,
  MdFormatAlignLeft,
  // Web / language
  MdLanguage,
  // Chat / groups
  MdChat,
  MdForum,
  MdFolder,
  MdGroup,
  MdKey,
  // Overflow / misc
  MdMoreVert,
  MdAttachFile,
  MdPhoto,
  // Arrows
  MdArrowDropDown,
  MdArrowDropUp,
  MdArrowUpward,
  MdArrowDownward,
  // Child lock / visibility
  MdLock,
  MdAccessTime,
  MdVisibility,
  MdVisibilityOff,
  // Sync
  MdSync,
  MdCloudSync,
  // Person
  MdPerson,
  // Link
  MdLink,
} from 'react-icons/md'
