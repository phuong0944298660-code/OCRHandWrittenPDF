import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.ocr_pipeline import PaddleEndpointClient, extract_boxes_from_det_response, extract_text_from_rec_response, should_use_detection


class OcrPipelineTest(unittest.TestCase):

    def test_only_multiline_fields_use_detection_before_recognition(self):
        self.assertFalse(should_use_detection({"field_type": "text"}))
        self.assertFalse(should_use_detection({"field_type": "text_cells"}))
        self.assertTrue(should_use_detection({"field_type": "multiline_text"}))
        self.assertTrue(should_use_detection({"field_type": "repeatable_text"}))
        self.assertTrue(should_use_detection({"field_type": "repeatable_date_month"}))

    def test_extracts_text_and_confidence_from_common_recognition_shapes(self):
        text, confidence = extract_text_from_rec_response({
            "result": [{"transcription": "KUSUMA", "score": 0.98}]
        })

        self.assertEqual(text, "KUSUMA")
        self.assertEqual(confidence, 0.98)

        text, confidence = extract_text_from_rec_response({
            "data": {"text": "DEWI ANGGRAINI", "confidence": 0.93}
        })

        self.assertEqual(text, "DEWI ANGGRAINI")
        self.assertEqual(confidence, 0.93)

    def test_text_without_model_score_uses_review_level_confidence(self):
        text, confidence = extract_text_from_rec_response({"text": "KUSUMA"})

        self.assertEqual(text, "KUSUMA")
        self.assertEqual(confidence, 0.42)

    def test_recognized_text_is_returned_without_translation_or_trimming(self):
        text, confidence = extract_text_from_rec_response({"text": " KUSUMA/DEWI "})

        self.assertEqual(text, " KUSUMA/DEWI ")
        self.assertEqual(confidence, 0.42)

    def test_extracts_detection_boxes_from_bbox_and_points_shapes(self):
        boxes = extract_boxes_from_det_response({
            "result": [
                {"bbox": [10, 20, 80, 40]},
                {"points": [[5, 60], [120, 61], [121, 90], [4, 89]]},
            ]
        })

        self.assertEqual(boxes, [[10, 20, 80, 40], [4, 60, 121, 90]])

    def test_extracts_detection_boxes_from_ppocr_boxes_shape(self):
        boxes = extract_boxes_from_det_response({
            "boxes": [
                [[455, 1299], [593, 1299], [593, 1312], [455, 1312]],
                [[725, 87], [999, 87], [999, 98], [725, 98]],
            ],
            "count": 2,
        })

        self.assertEqual(boxes, [[725, 87, 999, 98], [455, 1299, 593, 1312]])

    def test_model_http_client_ignores_proxy_environment(self):
        self.assertFalse(PaddleEndpointClient("http://det", "http://rec")._trust_env())


if __name__ == "__main__":
    unittest.main()
