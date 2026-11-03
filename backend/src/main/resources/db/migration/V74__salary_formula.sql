-- 自定义工资公式（统计面板「工资计算」）。
--
-- 1. salary_calculation.formula：这次计算用的公式（SalaryFormula），和结果一起存成快照。
--    V73 的计算按固定规则 max(工时 × 时薪, 被引张数 × 单价) 算，回填成等价的公式。
--    时薪 / 单价两列从此不再写（新行为 NULL），只留着给 V73 的老行。
-- 2. salary_calculation_line.uploaded_photos：公式字段「上传张数」（进入这个选题的、他拍的图片张数）。
--    V73 的行没有这个数，为 0（老公式也用不到它）。
-- 3. hour_amount / adoption_amount / basis 是固定规则下的拆分，公式算法下没有意义，新行不再写
--    （金额列取默认 0，basis 为 NULL）。

ALTER TABLE salary_calculation ADD COLUMN formula VARCHAR(500) NULL;
UPDATE salary_calculation
SET formula = CONCAT('max(工时 * ', hourly_rate, ', 被引张数 * ', adoption_rate, ')');
ALTER TABLE salary_calculation MODIFY COLUMN formula VARCHAR(500) NOT NULL;
ALTER TABLE salary_calculation MODIFY COLUMN hourly_rate DECIMAL(10, 2) NULL;
ALTER TABLE salary_calculation MODIFY COLUMN adoption_rate DECIMAL(10, 2) NULL;

ALTER TABLE salary_calculation_line ADD COLUMN uploaded_photos INT NOT NULL DEFAULT 0;
ALTER TABLE salary_calculation_line MODIFY COLUMN basis VARCHAR(16) NULL;
