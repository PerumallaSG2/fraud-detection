"""Mapping tests for prepare.py. Run: python3 -m unittest discover -s accuracy"""
import unittest

import prepare

ROW = {
    "trans_date_trans_time": "2020-06-21 12:14:25", "cc_num": "2291163933867244",
    "merchant": "fraud_Kirlin and Sons", "category": "personal_care", "amt": "2.86", "first": "Jeff",
    "last": "Elliott", "gender": "M", "street": "351 Darlene Green", "city": "Columbia", "state": "SC",
    "zip": "29209", "lat": "33.9659", "long": "-80.9355", "city_pop": "333497", "job": "Mechanical engineer",
    "dob": "1968-03-19", "trans_num": "2da90c7d74bd46a0caf3777415b3ebd3", "unix_time": "1371816865",
    "merch_lat": "33.986391", "merch_long": "-81.200714", "is_fraud": "0",
}


class PrepareTest(unittest.TestCase):

    def test_maps_fields(self):
        payload, _ = prepare.to_payload(ROW)
        self.assertEqual(payload, {
            "cardId": prepare.card_id("2291163933867244"), "amount": 2.86, "merchant": "Kirlin and Sons",
            "merchantCategory": "PERSONAL_CARE", "city": "Columbia", "latitude": 33.986391,
            "longitude": -81.200714, "timestamp": "2020-06-21T12:14:25-04:00",
        })

    def test_no_personal_fields_or_label_or_card_number(self):
        payload, _ = prepare.to_payload(ROW)
        values = " ".join(str(v) for v in payload.values())
        for field in ("first", "last", "street", "zip", "dob", "job", "cc_num", "trans_num", "is_fraud", "state"):
            self.assertNotIn(field, payload)
        for value in ("Jeff", "Elliott", "Darlene", "29209", "1968", "engineer", "2291163933867244"):
            self.assertNotIn(value, values)

    def test_card_id_is_stable_prefixed_pseudonym(self):
        self.assertEqual(prepare.card_id("123"), prepare.card_id("123"))
        self.assertNotEqual(prepare.card_id("123"), prepare.card_id("124"))
        self.assertRegex(prepare.card_id("123"), r"^sp-[0-9a-f]{16}$")

    def test_offset_follows_state_and_dst_keeping_wall_clock(self):
        summer = prepare.local_timestamp("2020-07-01 02:30:00", "CA").isoformat()
        winter = prepare.local_timestamp("2020-12-01 02:30:00", "CA").isoformat()
        arizona = prepare.local_timestamp("2020-07-01 02:30:00", "AZ").isoformat()
        self.assertEqual(summer, "2020-07-01T02:30:00-07:00")
        self.assertEqual(winter, "2020-12-01T02:30:00-08:00")
        self.assertEqual(arizona, "2020-07-01T02:30:00-07:00")

    def test_merchant_without_prefix_is_kept(self):
        self.assertEqual(prepare.merchant_name("Acme"), "Acme")

    def test_rejects_amounts_the_api_would_reject(self):
        for amt in ("0", "1.234", "-5.00", "abc"):
            with self.assertRaises(ValueError):
                prepare.to_payload({**ROW, "amt": amt})


if __name__ == "__main__":
    unittest.main()
