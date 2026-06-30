package com.tombo.billyassistant.companion.auth

object GoogleApiScopes {
    const val OPENID = "openid"
    const val EMAIL = "email"
    const val PROFILE = "profile"

    const val CALENDAR = "https://www.googleapis.com/auth/calendar"
    const val TASKS = "https://www.googleapis.com/auth/tasks"
    const val TASKS_READONLY = "https://www.googleapis.com/auth/tasks.readonly"
    const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
    const val GMAIL_COMPOSE = "https://www.googleapis.com/auth/gmail.compose"
    const val GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"
    const val DRIVE_METADATA_READONLY = "https://www.googleapis.com/auth/drive.metadata.readonly"
    const val DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    const val KEEP = "https://www.googleapis.com/auth/keep"
    const val KEEP_READONLY = "https://www.googleapis.com/auth/keep.readonly"
    const val DOCS = "https://www.googleapis.com/auth/documents"
    const val DOCS_READONLY = "https://www.googleapis.com/auth/documents.readonly"
    const val SHEETS = "https://www.googleapis.com/auth/spreadsheets"
    const val SHEETS_READONLY = "https://www.googleapis.com/auth/spreadsheets.readonly"
    const val SLIDES = "https://www.googleapis.com/auth/presentations"
    const val SLIDES_READONLY = "https://www.googleapis.com/auth/presentations.readonly"
    const val PHOTOS_PICKER_READONLY = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
    const val PHOTOS_LIBRARY_APP_CREATED_READONLY = "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata"
    const val CONTACTS_READONLY = "https://www.googleapis.com/auth/contacts.readonly"
    const val FORMS_BODY = "https://www.googleapis.com/auth/forms.body"
    const val FORMS_BODY_READONLY = "https://www.googleapis.com/auth/forms.body.readonly"
    const val FORMS_RESPONSES_READONLY = "https://www.googleapis.com/auth/forms.responses.readonly"

    val identity = listOf(OPENID, EMAIL, PROFILE)
    val calendar = identity + CALENDAR
    val tasks = identity + TASKS
    val gmail = identity + listOf(GMAIL_READONLY, GMAIL_COMPOSE, GMAIL_SEND)
    val drive = identity + listOf(DRIVE_METADATA_READONLY, DRIVE_READONLY, DRIVE_FILE)
    val people = identity + CONTACTS_READONLY
    val keep = identity + listOf(KEEP, KEEP_READONLY)
    val docs = identity + listOf(DOCS, DOCS_READONLY)
    val sheets = identity + listOf(SHEETS, SHEETS_READONLY)
    val slides = identity + listOf(SLIDES, SLIDES_READONLY)
    val forms = identity + FORMS_BODY_READONLY
    val photos = identity + listOf(PHOTOS_PICKER_READONLY, PHOTOS_LIBRARY_APP_CREATED_READONLY)
    val allUseful = (calendar + tasks + gmail + drive + people + docs + sheets + slides + forms + photos).distinct()
    val ASSISTANT_API_ACCESS = allUseful
}
