package com.aktcl.aron.backend.sync

import com.aktcl.aron.contract.RecordOutcomeCode

/**
 * Bangla and English text per record outcome code (bundle `reason_texts`, docs/24 s4.5): what the phone shows in
 * Sync Status for a rejected or quarantined row. One entry per [RecordOutcomeCode]; `ReasonTextsTest` fails the build
 * when a code has no text.
 */
object ReasonTexts {
    val ALL: Map<String, ReasonText> = mapOf(
        RecordOutcomeCode.SCOPE_STALE to ("রুট বা দোকান আপনার বর্তমান তালিকায় নেই; নতুন ডেটা আসার পর আবার পাঠানো হবে।" to "Route or outlet is not in your current list; it will be resent after the next download."),
        RecordOutcomeCode.CONFIG_VERSION_UNKNOWN to ("সার্ভার এখনো নতুন সেটিং পায়নি; কিছুক্ষণ পর আবার পাঠানো হবে।" to "The server does not have this settings version yet; it will be resent shortly."),
        RecordOutcomeCode.PARENT_MISSING to ("মূল রেকর্ড এখনো সার্ভারে পৌঁছায়নি; পরে আবার পাঠানো হবে।" to "The parent record has not reached the server yet; it will be resent later."),
        RecordOutcomeCode.OUTLET_PENDING_APPROVAL to ("নতুন দোকানটি এখনো অনুমোদিত হয়নি; অনুমোদনের পর পাঠানো হবে।" to "The new outlet is not approved yet; it will be sent after approval."),
        RecordOutcomeCode.PRICE_LIST_UNKNOWN to ("এই দামের তালিকা সার্ভারে এখনো দেখা যাচ্ছে না; পরে আবার পাঠানো হবে।" to "This price list is not visible on the server yet; it will be resent later."),
        RecordOutcomeCode.SCHEMA_INVALID to ("রেকর্ডটির গঠন সঠিক নয়; সাপোর্টে জানান।" to "The record is malformed; please contact support."),
        RecordOutcomeCode.UNKNOWN_RECORD_TYPE to ("এই ধরনের রেকর্ড সার্ভার চেনে না; অ্যাপ আপডেট করুন।" to "The server does not know this record type; please update the app."),
        RecordOutcomeCode.UNKNOWN_SKU to ("পণ্যটি তালিকায় নেই।" to "The product is not in the list."),
        RecordOutcomeCode.UNKNOWN_OUTLET to ("দোকানটি তালিকায় নেই।" to "The outlet is not in the list."),
        RecordOutcomeCode.UNKNOWN_ROUTE to ("রুটটি তালিকায় নেই।" to "The route is not in the list."),
        RecordOutcomeCode.ARITHMETIC_MISMATCH to ("মেমোর হিসাব মিলছে না; পর্যালোচনার জন্য রাখা হয়েছে।" to "The memo totals do not add up; held for review."),
        RecordOutcomeCode.MEMO_NO_INVALID to ("মেমো নম্বরটি সঠিক নয়।" to "The memo number is not valid."),
        RecordOutcomeCode.MEMO_NO_DUPLICATE to ("এই মেমো নম্বর আগেই ব্যবহার হয়েছে; পর্যালোচনার জন্য রাখা হয়েছে।" to "This memo number was already used; held for review."),
        RecordOutcomeCode.EDIT_NOT_ALLOWED to ("এখন এই মেমো সংশোধন করা যাবে না।" to "This memo can no longer be edited."),
        RecordOutcomeCode.CHAIN_TOO_DEEP to ("একই মেমো অনেকবার সংশোধন করা হয়েছে।" to "This memo has been edited too many times."),
        RecordOutcomeCode.VOIDED_BY_ADMIN to ("এই দিনের ডেটা অ্যাডমিন বাতিল করেছেন।" to "This day's data was voided by an administrator."),
        RecordOutcomeCode.CONTENT_DUPLICATE to ("একই বিক্রি দুবার এসেছে; পর্যালোচনার জন্য রাখা হয়েছে।" to "The same sale arrived twice; held for review."),
        RecordOutcomeCode.LINES_EXCEED_MAX to ("মেমোতে অনুমোদিত সংখ্যার বেশি লাইন আছে।" to "The memo has more lines than allowed."),
        RecordOutcomeCode.QTY_INVALID to ("পরিমাণ সঠিক নয়।" to "The quantity is not valid."),
        RecordOutcomeCode.ATTENDANCE_DUPLICATE to ("আজকের হাজিরা আগেই দেওয়া হয়েছে।" to "Attendance for today was already recorded."),
        RecordOutcomeCode.CHECKOUT_TOO_EARLY to ("নির্ধারিত সময়ের আগে চেক-আউট; পর্যালোচনার জন্য রাখা হয়েছে।" to "Check-out before the allowed time; held for review."),
        RecordOutcomeCode.SERVER_ERROR to ("সার্ভারে সমস্যা হয়েছে; পরে আবার পাঠানো হবে।" to "Server problem; it will be resent later."),
        RecordOutcomeCode.PAYLOAD_CONFLICT to ("একই রেকর্ড ভিন্ন তথ্যসহ এসেছে; পর্যালোচনার জন্য রাখা হয়েছে।" to "The same record arrived with different data; held for review."),
        RecordOutcomeCode.BUSINESS_DATE_OUT_OF_WINDOW to ("তারিখ অনুমোদিত সময়সীমার বাইরে; পর্যালোচনার জন্য রাখা হয়েছে।" to "The date is outside the allowed window; held for review."),
        RecordOutcomeCode.SCOPE_OUT_OF_REACH to ("রুট বা দোকান আপনার এলাকার বাইরে; পর্যালোচনার জন্য রাখা হয়েছে।" to "Route or outlet is outside your area; held for review."),
        RecordOutcomeCode.NO_ASSIGNMENT_ON_DATE to ("ওই তারিখে এই রুট আপনার নামে ছিল না; পর্যালোচনার জন্য রাখা হয়েছে।" to "You were not assigned to this route on that date; held for review."),
        RecordOutcomeCode.DEVICE_INTEGRITY_FAILED to ("ফোনের নিরাপত্তা যাচাই ব্যর্থ; পর্যালোচনার জন্য রাখা হয়েছে।" to "The phone failed the integrity check; held for review."),
        RecordOutcomeCode.DEVICE_NOT_ENROLLED to ("ফোনটি নিবন্ধিত নয়; পর্যালোচনার জন্য রাখা হয়েছে।" to "The phone is not enrolled; held for review."),
        RecordOutcomeCode.USER_DISABLED to ("ব্যবহারকারী নিষ্ক্রিয়; পর্যালোচনার জন্য রাখা হয়েছে।" to "The user is disabled; held for review."),
        RecordOutcomeCode.DEVICE_REVOKED to ("ফোনটির অনুমতি বাতিল; পর্যালোচনার জন্য রাখা হয়েছে।" to "The phone has been revoked; held for review."),
        RecordOutcomeCode.APP_VERSION_BLOCKED to ("অ্যাপের এই সংস্করণ বন্ধ; অ্যাপ আপডেট করুন।" to "This app version is blocked; please update the app."),
        RecordOutcomeCode.AFTER_MONTH_CLOSE to ("মাস বন্ধ হওয়ার পর এসেছে; পর্যালোচনার জন্য রাখা হয়েছে।" to "Arrived after the month was closed; held for review."),
        RecordOutcomeCode.UNKNOWN_GIFT to ("উপহারটি তালিকায় নেই।" to "The gift is not in the list."),
        RecordOutcomeCode.INSUFFICIENT_POINTS to ("যথেষ্ট পয়েন্ট নেই।" to "Not enough points."),
        RecordOutcomeCode.GIFT_PHOTO_EXISTS to ("এই উপহারের ছবি আগেই তোলা হয়েছে।" to "A photo for this gift was already captured."),
        RecordOutcomeCode.PROGRAMME_INACTIVE to ("প্রোগ্রামটি চালু নেই; পর্যালোচনার জন্য রাখা হয়েছে।" to "The programme is not active; held for review."),
    ).entries.associate { (code, t) -> code.wire to ReasonText(bn = t.first, en = t.second) }
}
