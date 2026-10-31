//! Reading record attributes the way the Java module read its rows.

use chrono::{DateTime, Utc};
use serde_json::{Map, Value};

/// A value as text: a non-blank string as-is, a number or boolean in its JSON form, anything else
/// `None` (Java's `asString` with its blank check).
pub fn text(value: Option<&Value>) -> Option<String> {
    match value? {
        Value::String(s) if !s.trim().is_empty() => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

/// [`text`] of `map[key]`.
pub fn text_of(map: &Map<String, Value>, key: &str) -> Option<String> {
    text(map.get(key))
}

/// A trimmed, non-blank string input (`ActionInputs.string`).
pub fn input(map: &Map<String, Value>, key: &str) -> Option<String> {
    text(map.get(key)).map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
}

/// An RFC 3339 timestamp, or `None` when absent or unreadable.
pub fn instant(value: Option<&Value>) -> Option<DateTime<Utc>> {
    match value? {
        Value::String(s) => DateTime::parse_from_rfc3339(s.trim()).ok().map(|d| d.with_timezone(&Utc)),
        _ => None,
    }
}

/// An RFC 3339 timestamp with millisecond precision and `Z`, as the platform stores them.
pub fn format_instant(at: DateTime<Utc>) -> String {
    at.to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}

/// A JSON object stored in a `JSON` field: an object as-is, or a string holding one.
pub fn json_object(value: Option<&Value>) -> Option<Map<String, Value>> {
    match value? {
        Value::Object(map) => Some(map.clone()),
        Value::String(s) if !s.trim().is_empty() => match serde_json::from_str::<Value>(s) {
            Ok(Value::Object(map)) => Some(map),
            _ => None,
        },
        _ => None,
    }
}

/// An integer from a number or a numeric string (`Integer.valueOf`); fractions are truncated.
pub fn integer(value: Option<&Value>) -> Option<i64> {
    match value? {
        Value::Number(n) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)),
        Value::String(s) => s.trim().parse::<i32>().ok().map(i64::from),
        _ => None,
    }
}

/// `value` is exactly JSON `true`.
pub fn is_true(value: Option<&Value>) -> bool {
    matches!(value, Some(Value::Bool(true)))
}

/// A canonical, lower-case UUID string check, like the Java module's pattern.
pub fn is_uuid(value: &str) -> bool {
    value.len() == 36
        && value.char_indices().all(|(i, c)| match i {
            8 | 13 | 18 | 23 => c == '-',
            _ => c.is_ascii_hexdigit(),
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn text_matches_java_as_string() {
        assert_eq!(text(Some(&json!("a"))), Some("a".into()));
        assert_eq!(text(Some(&json!("  "))), None);
        assert_eq!(text(Some(&json!(3))), Some("3".into()));
        assert_eq!(text(Some(&Value::Null)), None);
        assert_eq!(text(None), None);
        assert_eq!(input(&json!({"k": "  v  "}).as_object().unwrap().clone(), "k"), Some("v".into()));
    }

    #[test]
    fn instants_parse_rfc3339() {
        let at = instant(Some(&json!("2026-01-02T03:04:05Z"))).unwrap();
        assert_eq!(format_instant(at), "2026-01-02T03:04:05.000Z");
        assert!(instant(Some(&json!("yesterday"))).is_none());
        assert!(instant(Some(&json!(5))).is_none());
    }

    #[test]
    fn json_objects_come_as_objects_or_strings() {
        assert_eq!(json_object(Some(&json!({"a": 1}))), json!({"a": 1}).as_object().cloned());
        assert_eq!(json_object(Some(&json!("{\"a\":1}"))), json!({"a": 1}).as_object().cloned());
        assert_eq!(json_object(Some(&json!("[1]"))), None);
        assert_eq!(json_object(Some(&json!(1))), None);
    }

    #[test]
    fn integers_follow_integer_value_of() {
        assert_eq!(integer(Some(&json!(30))), Some(30));
        assert_eq!(integer(Some(&json!(2.9))), Some(2));
        assert_eq!(integer(Some(&json!(" 7 "))), Some(7));
        assert_eq!(integer(Some(&json!("7.5"))), None);
        assert_eq!(integer(Some(&json!(true))), None);
    }

    #[test]
    fn uuids() {
        assert!(is_uuid("123e4567-e89b-12d3-a456-426614174000"));
        assert!(is_uuid("123E4567-E89B-12D3-A456-426614174000"));
        assert!(!is_uuid("member@example.test"));
        assert!(!is_uuid("123e4567e89b12d3a456426614174000"));
    }
}
