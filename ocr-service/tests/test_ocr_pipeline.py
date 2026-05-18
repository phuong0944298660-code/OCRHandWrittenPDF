import sys
import unittest
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.ocr_pipeline import (
    clean_recognized_field_text,
    extract_boxes_from_det_response,
    extract_text_from_rec_response,
    parse_ocr_params,
    prepare_text_recognition_crop,
    recognize_document,
    should_keep_child_text,
    should_use_detection,
)

from PIL import Image, ImageDraw


class FakeBatchModelClient:
    def __init__(self):
        self.recognize_many_calls = []
        self.detect_many_calls = []

    def recognize(self, image_bytes):
        raise AssertionError("recognize should not be called when batch recognition is available")

    def detect(self, image_bytes):
        raise AssertionError("detect should not be called when batch detection is available")

    def recognize_many(self, image_bytes_list):
        self.recognize_many_calls.append(list(image_bytes_list))
        return [(f"VALUE-{index + 1}", 0.91 - index * 0.01) for index, _ in enumerate(image_bytes_list)]

    def detect_many(self, image_bytes_list):
        self.detect_many_calls.append(list(image_bytes_list))
        return [[] for _ in image_bytes_list]


class FakeMultilineBatchModelClient(FakeBatchModelClient):
    def detect_many(self, image_bytes_list):
        self.detect_many_calls.append(list(image_bytes_list))
        return [
            [[1, 1, 40, 18]]
            for _ in image_bytes_list
        ]


class FakeRepeatableBatchModelClient(FakeBatchModelClient):
    def detect_many(self, image_bytes_list):
        self.detect_many_calls.append(list(image_bytes_list))
        return [[
            [5, 10, 120, 35],
            [5, 48, 120, 75],
            [5, 170, 120, 198],
            [5, 210, 120, 240],
        ]]

    def recognize_many(self, image_bytes_list):
        self.recognize_many_calls.append(list(image_bytes_list))
        values = ["Mrs. Aisha", "TA", "Mr. Mohammad", "RASHID"]
        return [(values[index], 0.90) for index, _ in enumerate(image_bytes_list)]


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

    def test_extracts_detection_boxes_from_local_paddle_result_shape(self):
        boxes = extract_boxes_from_det_response({
            "dt_polys": [
                [[8, 89], [154, 89], [154, 102], [8, 102]],
                [[9, 20], [70, 20], [70, 31], [9, 31]],
            ],
            "dt_scores": [0.89, 0.98],
        })

        self.assertEqual(boxes, [[9, 20, 70, 31], [8, 89, 154, 102]])

    def test_extracts_text_and_score_from_local_paddle_result_shape(self):
        text, confidence = extract_text_from_rec_response({
            "rec_text": "KUSMA",
            "rec_score": 0.8228,
            "input_img": [[[255, 255, 255]]],
        })

        self.assertEqual(text, "KUSMA")
        self.assertEqual(confidence, 0.8228)

    def test_parse_ocr_params_accepts_only_fixed_render_dpi_values(self):
        self.assertEqual(parse_ocr_params('{"render_dpi": 300}')["render_dpi"], 300)
        self.assertEqual(parse_ocr_params('{"render_dpi": 400}')["render_dpi"], 400)
        with self.assertRaises(ValueError):
            parse_ocr_params('{"render_dpi": 180}')

    def test_single_line_fields_are_recognized_in_one_batch(self):
        image = Image.new("RGB", (200, 300), "white")
        draw = ImageDraw.Draw(image)
        draw.rectangle((25, 35, 75, 50), fill="black")
        draw.rectangle((25, 95, 75, 110), fill="black")
        client = FakeBatchModelClient()

        result = recognize_document(
            task_id="task-1",
            template_id="id988a",
            file_bytes=image_bytes(image),
            fields=[
                text_field("field1.value", 0.1, 0.1, 0.4, 0.1),
                text_field("field2.value", 0.1, 0.3, 0.4, 0.1),
            ],
            model_client=client,
        )

        self.assertEqual(len(client.recognize_many_calls), 1)
        self.assertEqual(len(client.recognize_many_calls[0]), 2)
        self.assertEqual([field["value"] for field in result["fields"]], ["VALUE-1", "VALUE-2"])

    def test_multiline_fields_batch_detect_then_batch_recognize_child_crops(self):
        image = Image.new("RGB", (200, 300), "white")
        draw = ImageDraw.Draw(image)
        draw.rectangle((25, 35, 75, 50), fill="black")
        draw.rectangle((25, 125, 75, 140), fill="black")
        client = FakeMultilineBatchModelClient()

        result = recognize_document(
            task_id="task-1",
            template_id="id988a",
            file_bytes=image_bytes(image),
            fields=[
                text_field("address1.value", 0.1, 0.1, 0.4, 0.18, field_type="multiline_text"),
                text_field("address2.value", 0.1, 0.4, 0.4, 0.18, field_type="multiline_text"),
            ],
            model_client=client,
        )

        self.assertEqual(len(client.detect_many_calls), 1)
        self.assertEqual(len(client.detect_many_calls[0]), 2)
        self.assertEqual(len(client.recognize_many_calls), 1)
        self.assertEqual(len(client.recognize_many_calls[0]), 2)
        self.assertEqual([field["source"] for field in result["fields"]], ["ppocr_det_rec", "ppocr_det_rec"])

    def test_working_experience_repeatable_field_keeps_two_entries_separated(self):
        image = Image.new("RGB", (300, 420), "white")
        draw = ImageDraw.Draw(image)
        draw.rectangle((20, 120, 90, 135), fill="black")
        draw.rectangle((20, 260, 90, 275), fill="black")
        client = FakeRepeatableBatchModelClient()

        result = recognize_document(
            task_id="task-1",
            template_id="id988a",
            file_bytes=image_bytes(image),
            fields=[text_field("workingExperience.items[].employerName.value", 0.05, 0.25, 0.55, 0.55, field_type="repeatable_text")],
            model_client=client,
        )

        self.assertEqual(result["fields"][0]["value"], "Mrs. Aisha TA|Mr. Mohammad RASHID")

    def test_empty_text_roi_is_returned_without_model_call(self):
        image = Image.new("RGB", (200, 300), "white")
        draw = ImageDraw.Draw(image)
        draw.line((20, 60, 100, 60), fill="black", width=1)
        draw.line((20, 30, 20, 90), fill="black", width=1)
        client = FakeBatchModelClient()

        result = recognize_document(
            task_id="task-1",
            template_id="id988a",
            file_bytes=image_bytes(image),
            fields=[text_field("blank.value", 0.1, 0.1, 0.4, 0.2)],
            model_client=client,
        )

        self.assertEqual(client.recognize_many_calls, [])
        self.assertEqual(result["fields"][0]["source"], "empty_roi")
        self.assertFalse(result["fields"][0]["present"])

    def test_blank_checkbox_border_is_not_treated_as_checked(self):
        image = Image.new("RGB", (200, 300), "white")
        draw = ImageDraw.Draw(image)
        draw.rectangle((70, 90, 86, 106), outline="black", width=2)
        draw.line((60, 86, 150, 86), fill="black", width=1)
        draw.line((60, 110, 150, 110), fill="black", width=1)
        client = FakeBatchModelClient()

        result = recognize_document(
            task_id="task-1",
            template_id="id988a",
            file_bytes=image_bytes(image),
            fields=[checkbox_field("applicationType.contractRenewal.entryVisa.checked", 0.35, 0.30, 0.08, 0.06)],
            model_client=client,
        )

        self.assertEqual(client.recognize_many_calls, [])
        self.assertEqual(result["fields"][0]["source"], "visual_checkbox")
        self.assertEqual(result["fields"][0]["value"], "unchecked")
        self.assertFalse(result["fields"][0]["present"])

    def test_checked_checkbox_uses_inner_mark_not_box_border(self):
        image = Image.new("RGB", (200, 300), "white")
        draw = ImageDraw.Draw(image)
        draw.rectangle((70, 90, 86, 106), outline="black", width=2)
        draw.line((73, 98, 78, 103), fill="black", width=3)
        draw.line((78, 103, 85, 92), fill="black", width=3)
        client = FakeBatchModelClient()

        result = recognize_document(
            task_id="task-1",
            template_id="id988a",
            file_bytes=image_bytes(image),
            fields=[checkbox_field("applicationType.entryFromAbroad.entryVisa.checked", 0.35, 0.30, 0.08, 0.06)],
            model_client=client,
        )

        self.assertEqual(client.recognize_many_calls, [])
        self.assertEqual(result["fields"][0]["source"], "visual_checkbox")
        self.assertEqual(result["fields"][0]["value"], "checked")
        self.assertTrue(result["fields"][0]["present"])

    def test_page_bottom_signature_is_recognized_as_text(self):
        image = Image.new("RGB", (300, 420), "white")
        draw = ImageDraw.Draw(image)
        draw.text((185, 366), "KUSUMA DEWI", fill="black")
        client = FakeBatchModelClient()

        result = recognize_document(
            task_id="task-1",
            template_id="id988a",
            file_bytes=image_bytes(image),
            fields=[text_field("page1Confirmation.signature.present", 0.58, 0.84, 0.36, 0.08, field_type="signature_presence")],
            model_client=client,
        )

        self.assertEqual(len(client.recognize_many_calls), 1)
        self.assertEqual(result["fields"][0]["source"], "ppocr_rec")
        self.assertEqual(result["fields"][0]["value"], "VALUE-1")
        self.assertTrue(result["fields"][0]["present"])

    def test_landscape_page_fails_alignment_and_skips_model_call(self):
        image = Image.new("RGB", (300, 200), "white")
        draw = ImageDraw.Draw(image)
        draw.rectangle((30, 30, 120, 60), fill="black")
        client = FakeBatchModelClient()

        result = recognize_document(
            task_id="task-1",
            template_id="id988a",
            file_bytes=image_bytes(image),
            fields=[text_field("rotated.value", 0.1, 0.1, 0.4, 0.2)],
            model_client=client,
        )

        self.assertEqual(client.recognize_many_calls, [])
        self.assertEqual(result["fields"][0]["source"], "page_alignment_failed")
        self.assertTrue(result["fields"][0]["present"])

    def test_text_recognition_crop_removes_edge_label_fragments(self):
        image = Image.new("RGB", (420, 90), "white")
        draw = ImageDraw.Draw(image)
        draw.text((0, 34), "ish", fill="black")
        draw.rectangle((65, 15, 185, 65), fill="black")

        crop = prepare_text_recognition_crop(image, {"key": "surnameEn.value", "field_type": "text"})
        pixels = crop.convert("L")

        self.assertLess(crop.width, image.width)
        self.assertFalse(any(pixels.getpixel((x, y)) < 190 for x in range(0, 12) for y in range(crop.height)))
        self.assertTrue(any(pixels.getpixel((x, y)) < 190 for x in range(15, crop.width) for y in range(crop.height)))

    def test_date_cell_recognition_crop_removes_bottom_format_labels(self):
        image = Image.new("RGB", (420, 110), "white")
        draw = ImageDraw.Draw(image)
        draw.rectangle((55, 8, 105, 35), fill="black")
        draw.rectangle((170, 8, 220, 35), fill="black")
        draw.text((60, 78), "dd", fill="black")
        draw.text((175, 78), "mm", fill="black")

        crop = prepare_text_recognition_crop(image, {"key": "dateOfBirth.value", "field_type": "date_cells"})
        pixels = crop.convert("L")

        self.assertLess(crop.height, image.height)
        self.assertFalse(any(pixels.getpixel((x, y)) < 190 for x in range(crop.width) for y in range(max(0, crop.height - 12), crop.height)))
        self.assertTrue(any(pixels.getpixel((x, y)) < 190 for x in range(crop.width) for y in range(0, min(55, crop.height))))

    def test_occupation_multiline_filter_drops_label_fragments(self):
        self.assertFalse(should_keep_child_text({"key": "occupation.value"}, "on"))
        self.assertTrue(should_keep_child_text({"key": "occupation.value"}, "Domestic"))
        self.assertTrue(should_keep_child_text({"key": "placeOfBirth.value"}, "Jl"))

    def test_place_of_issue_removes_edge_label_fragment(self):
        self.assertEqual(
            clean_recognized_field_text({"key": "travelDocument.placeOfIssue.value"}, "eJorkarta"),
            "Jorkarta",
        )

    def test_contact_telephone_removes_printed_no_prefix(self):
        self.assertEqual(
            clean_recognized_field_text({"key": "contactTelephone.no.value"}, "no.+62 81345678910"),
            "+62 81345678910",
        )


def text_field(key, x, y, width, height, field_type="text"):
    box = {"x": x, "y": y, "width": width, "height": height}
    return {
        "page": 1,
        "key": key,
        "field_type": field_type,
        "group_box": box,
        "value_box": box,
    }


def checkbox_field(key, x, y, width, height):
    return text_field(key, x, y, width, height, field_type="checkbox")


def image_bytes(image):
    output = BytesIO()
    image.save(output, format="PNG")
    return output.getvalue()


if __name__ == "__main__":
    unittest.main()
