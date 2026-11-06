use std::path::Path;

use image::codecs::jpeg::JpegEncoder;
use image::{ExtendedColorType, GenericImageView};
use serde_json::{json, Value};

pub const WEB_MAP_MAX_EDGE: u32 = 2560;
const JPEG_QUALITY: u8 = 82;

#[derive(Debug, Clone)]
pub struct PreparedMap {
    pub bytes: Vec<u8>,
    pub file_name: String,
    pub mime: String,
    pub width: i32,
    pub height: i32,
    pub source_width: i32,
}

pub fn published_size(src_width: u32, src_height: u32) -> (u32, u32) {
    let long = src_width.max(src_height);
    if long == 0 || long <= WEB_MAP_MAX_EDGE {
        return (src_width.max(1), src_height.max(1));
    }
    let scale = f64::from(WEB_MAP_MAX_EDGE) / f64::from(long);
    let width = ((f64::from(src_width) * scale).round() as u32).max(1);
    let height = ((f64::from(src_height) * scale).round() as u32).max(1);
    (width, height)
}

pub fn scale_meters_per_pixel(meters_per_pixel: f64, from_width: i32, to_width: i32) -> f64 {
    if !(meters_per_pixel.is_finite() && meters_per_pixel > 0.0)
        || from_width <= 0
        || to_width <= 0
        || from_width == to_width
    {
        return meters_per_pixel;
    }
    meters_per_pixel * (f64::from(from_width) / f64::from(to_width))
}

pub fn prepare_web_map(bytes: &[u8], file_name: &str, mime: Option<&str>) -> PreparedMap {
    let original_name = sanitize_map_file_name(file_name);
    let original_mime = mime
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .unwrap_or("application/octet-stream")
        .to_string();
    let fallback = |width: i32, height: i32| PreparedMap {
        bytes: bytes.to_vec(),
        file_name: original_name.clone(),
        mime: original_mime.clone(),
        width,
        height,
        source_width: width,
    };
    let img = match image::load_from_memory(bytes) {
        Ok(img) => img,
        Err(_) => return fallback(0, 0),
    };
    let (src_width, src_height) = img.dimensions();
    if src_width == 0 || src_height == 0 {
        return fallback(0, 0);
    }
    let (out_width, out_height) = published_size(src_width, src_height);
    let already_jpeg = is_jpeg(&original_name, &original_mime);
    if out_width == src_width && out_height == src_height && already_jpeg {
        return PreparedMap {
            bytes: bytes.to_vec(),
            file_name: jpeg_file_name(&original_name),
            mime: "image/jpeg".into(),
            width: src_width as i32,
            height: src_height as i32,
            source_width: src_width as i32,
        };
    }
    let resized = if out_width == src_width && out_height == src_height {
        img
    } else {
        img.resize_exact(out_width, out_height, image::imageops::FilterType::Lanczos3)
    };
    let rgb = resized.to_rgb8();
    let mut encoded = Vec::new();
    let encode_ok = JpegEncoder::new_with_quality(&mut encoded, JPEG_QUALITY)
        .encode(
            rgb.as_raw(),
            rgb.width(),
            rgb.height(),
            ExtendedColorType::Rgb8,
        )
        .is_ok();
    if !encode_ok || encoded.is_empty() {
        return PreparedMap {
            bytes: bytes.to_vec(),
            file_name: original_name,
            mime: original_mime,
            width: src_width as i32,
            height: src_height as i32,
            source_width: src_width as i32,
        };
    }
    PreparedMap {
        bytes: encoded,
        file_name: jpeg_file_name(&original_name),
        mime: "image/jpeg".into(),
        width: out_width as i32,
        height: out_height as i32,
        source_width: src_width as i32,
    }
}

pub fn sync_pack_map_geometry(pack: &mut Value, published_width: i32, published_height: i32) {
    if published_width <= 0 || published_height <= 0 {
        return;
    }
    let Some(obj) = pack.as_object_mut() else {
        return;
    };
    let current_width = json_positive_i32(obj.get("mapWidth").or_else(|| obj.get("map_width")));
    if let Some(mpp) = json_positive_f64(
        obj.get("metersPerPixel")
            .or_else(|| obj.get("meters_per_pixel")),
    ) {
        let from_width = current_width.unwrap_or(published_width);
        obj.insert(
            "metersPerPixel".into(),
            json!(scale_meters_per_pixel(mpp, from_width, published_width)),
        );
        obj.remove("meters_per_pixel");
    }
    obj.insert("mapWidth".into(), json!(published_width));
    obj.insert("mapHeight".into(), json!(published_height));
    obj.remove("map_width");
    obj.remove("map_height");
}

fn jpeg_file_name(raw: &str) -> String {
    let stem = Path::new(raw)
        .file_stem()
        .and_then(|s| s.to_str())
        .filter(|s| !s.is_empty())
        .unwrap_or("map");
    let cleaned: String = stem
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                c
            } else {
                '_'
            }
        })
        .collect();
    format!("{}.jpg", if cleaned.is_empty() { "map" } else { &cleaned })
}

fn sanitize_map_file_name(raw: &str) -> String {
    let name = Path::new(raw)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("map.bin");
    let cleaned: String = name
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_' {
                c
            } else {
                '_'
            }
        })
        .collect();
    if cleaned.is_empty() {
        "map.bin".into()
    } else {
        cleaned
    }
}

fn is_jpeg(file_name: &str, mime: &str) -> bool {
    mime.eq_ignore_ascii_case("image/jpeg")
        || mime.eq_ignore_ascii_case("image/jpg")
        || file_name.rsplit('.').next().is_some_and(|ext| {
            ext.eq_ignore_ascii_case("jpg") || ext.eq_ignore_ascii_case("jpeg")
        })
}

fn json_positive_i32(value: Option<&Value>) -> Option<i32> {
    let value = value?;
    let n = value
        .as_i64()
        .map(|n| n as f64)
        .or_else(|| value.as_f64())
        .or_else(|| value.as_str()?.trim().parse().ok())?;
    if n.is_finite() && n > 0.0 {
        Some(n.round() as i32)
    } else {
        None
    }
}

fn json_positive_f64(value: Option<&Value>) -> Option<f64> {
    let value = value?;
    let n = value
        .as_f64()
        .or_else(|| value.as_i64().map(|n| n as f64))
        .or_else(|| value.as_str()?.trim().parse().ok())?;
    (n.is_finite() && n > 0.0).then_some(n)
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{ImageBuffer, Rgb};

    #[test]
    fn published_size_keeps_small_images() {
        assert_eq!(published_size(1200, 800), (1200, 800));
    }

    #[test]
    fn published_size_fits_long_edge() {
        let (w, h) = published_size(5000, 3000);
        assert_eq!(w.max(h), WEB_MAP_MAX_EDGE);
        assert_eq!(w, 2560);
        assert_eq!(h, 1536);
    }

    #[test]
    fn meters_scale_with_width() {
        let scaled = scale_meters_per_pixel(2.0, 5000, 2500);
        assert!((scaled - 4.0).abs() < 1e-9);
        assert_eq!(scale_meters_per_pixel(2.0, 2500, 2500), 2.0);
    }

    #[test]
    fn pack_geometry_follows_published_image() {
        let mut pack = json!({
            "mapWidth": 5000,
            "mapHeight": 3000,
            "metersPerPixel": 1.5
        });
        sync_pack_map_geometry(&mut pack, 2500, 1500);
        assert_eq!(pack.get("mapWidth").and_then(|v| v.as_i64()), Some(2500));
        assert_eq!(pack.get("mapHeight").and_then(|v| v.as_i64()), Some(1500));
        let mpp = pack.get("metersPerPixel").and_then(|v| v.as_f64()).unwrap();
        assert!((mpp - 3.0).abs() < 1e-9);
    }

    #[test]
    fn prepare_web_map_downscales_png() {
        use image::codecs::png::PngEncoder;
        use image::ImageEncoder;

        let img = ImageBuffer::from_pixel(3000, 1200, Rgb([20u8, 80, 40]));
        let mut png = Vec::new();
        PngEncoder::new(&mut png)
            .write_image(
                img.as_raw(),
                img.width(),
                img.height(),
                ExtendedColorType::Rgb8,
            )
            .unwrap();
        let prepared = prepare_web_map(&png, "course.png", Some("image/png"));
        assert_eq!(prepared.source_width, 3000);
        assert_eq!(prepared.width, 2560);
        assert_eq!(prepared.height, 1024);
        assert_eq!(prepared.mime, "image/jpeg");
        assert_eq!(prepared.file_name, "course.jpg");
        let out = image::load_from_memory(&prepared.bytes).unwrap();
        assert_eq!(out.dimensions(), (2560, 1024));
    }
}
